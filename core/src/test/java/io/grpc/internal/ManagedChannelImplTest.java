/*
 * Copyright 2015, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static junit.framework.TestCase.assertNotSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.CallCredentials.MetadataApplier;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.Context;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.IntegerMarshaller;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.NameResolver;
import io.grpc.SecurityLevel;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StringMarshaller;
import io.grpc.internal.Channelz.ChannelStats;
import io.grpc.internal.NoopClientCall.NoopClientCallListener;
import io.grpc.internal.TestUtils.MockClientTransportInfo;
import io.grpc.internal.testing.SingleMessageProducer;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link ManagedChannelImpl}. */
@RunWith(JUnit4.class)
public class ManagedChannelImplTest {
  private static final List<ClientInterceptor> NO_INTERCEPTOR =
      Collections.<ClientInterceptor>emptyList();
  private static final Attributes NAME_RESOLVER_PARAMS =
      Attributes.newBuilder().set(NameResolver.Factory.PARAMS_DEFAULT_PORT, 447).build();
  private static final MethodDescriptor<String, Integer> method =
      MethodDescriptor.<String, Integer>newBuilder()
          .setType(MethodType.UNKNOWN)
          .setFullMethodName("service/method")
          .setRequestMarshaller(new StringMarshaller())
          .setResponseMarshaller(new IntegerMarshaller())
          .build();
  private static final Attributes.Key<String> SUBCHANNEL_ATTR_KEY =
      Attributes.Key.of("subchannel-attr-key");
  private static final long RECONNECT_BACKOFF_INTERVAL_NANOS = 10;
  private final String serviceName = "fake.example.com";
  private final String authority = serviceName;
  private final String userAgent = "userAgent";
  private final ProxyParameters noProxy = null;
  private final String target = "fake://" + serviceName;
  private URI expectedUri;
  private final SocketAddress socketAddress = new SocketAddress() {};
  private final EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(socketAddress);
  private final FakeClock timer = new FakeClock();
  private final FakeClock executor = new FakeClock();
  private final FakeClock oobExecutor = new FakeClock();
  private static final FakeClock.TaskFilter NAME_RESOLVER_REFRESH_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command instanceof ManagedChannelImpl.NameResolverRefresh;
        }
      };
  private final CallTracer.Factory channelStatsFactory = new CallTracer.Factory() {
    @Override
    public CallTracer create() {
      return new CallTracer(new CallTracer.TimeProvider() {
        @Override
        public long currentTimeMillis() {
          return executor.currentTimeMillis();
        }
      });
    }
  };
  private final Channelz channelz = new Channelz();

  @Rule public final ExpectedException thrown = ExpectedException.none();

  private ManagedChannelImpl channel;
  private Helper helper;
  @Captor
  private ArgumentCaptor<Status> statusCaptor;
  @Captor
  private ArgumentCaptor<CallOptions> callOptionsCaptor;
  @Mock
  private LoadBalancer.Factory mockLoadBalancerFactory;
  @Mock
  private LoadBalancer mockLoadBalancer;

  @Captor
  private ArgumentCaptor<ConnectivityStateInfo> stateInfoCaptor;
  @Mock
  private SubchannelPicker mockPicker;
  @Mock
  private ClientTransportFactory mockTransportFactory;
  @Mock
  private ClientCall.Listener<Integer> mockCallListener;
  @Mock
  private ClientCall.Listener<Integer> mockCallListener2;
  @Mock
  private ClientCall.Listener<Integer> mockCallListener3;
  @Mock
  private ClientCall.Listener<Integer> mockCallListener4;
  @Mock
  private ClientCall.Listener<Integer> mockCallListener5;
  @Mock
  private ObjectPool<Executor> executorPool;
  @Mock
  private ObjectPool<Executor> oobExecutorPool;
  @Mock
  private CallCredentials creds;
  private BinaryLogProvider binlogProvider = null;
  private BlockingQueue<MockClientTransportInfo> transports;

  private ArgumentCaptor<ClientStreamListener> streamListenerCaptor =
      ArgumentCaptor.forClass(ClientStreamListener.class);

  private void createChannel(
      NameResolver.Factory nameResolverFactory, List<ClientInterceptor> interceptors) {
    createChannel(
        nameResolverFactory, interceptors, true /* requestConnection */,
        ManagedChannelImpl.IDLE_TIMEOUT_MILLIS_DISABLE);
  }

  private void createChannel(
      NameResolver.Factory nameResolverFactory, List<ClientInterceptor> interceptors,
      boolean requestConnection, long idleTimeoutMillis) {
    class Builder extends AbstractManagedChannelImplBuilder<Builder> {
      Builder(String target) {
        super(target);
      }

      @Override protected ClientTransportFactory buildTransportFactory() {
        throw new UnsupportedOperationException();
      }

      @Override protected Attributes getNameResolverParams() {
        return NAME_RESOLVER_PARAMS;
      }

      @Override public Builder usePlaintext() {
        throw new UnsupportedOperationException();
      }
    }

    Builder builder = new Builder(target)
        .nameResolverFactory(nameResolverFactory)
        .loadBalancerFactory(mockLoadBalancerFactory)
        .userAgent(userAgent);
    builder.executorPool = executorPool;
    builder.idleTimeoutMillis = idleTimeoutMillis;
    builder.binlogProvider = binlogProvider;
    builder.channelz = channelz;
    checkState(channel == null);
    channel = new ManagedChannelImpl(
        builder, mockTransportFactory, new FakeBackoffPolicyProvider(),
        oobExecutorPool, timer.getStopwatchSupplier(), interceptors,
        channelStatsFactory);

    if (requestConnection) {
      int numExpectedTasks = 0;

      // Force-exit the initial idle-mode
      channel.exitIdleMode();
      if (idleTimeoutMillis != ManagedChannelImpl.IDLE_TIMEOUT_MILLIS_DISABLE) {
        numExpectedTasks += 1;
      }

      if (getNameResolverRefresh() != null) {
        numExpectedTasks += 1;
      }

      assertEquals(numExpectedTasks, timer.numPendingTasks());

      ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(null);
      verify(mockLoadBalancerFactory).newLoadBalancer(helperCaptor.capture());
      helper = helperCaptor.getValue();
    }
  }

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    expectedUri = new URI(target);
    when(mockLoadBalancerFactory.newLoadBalancer(any(Helper.class))).thenReturn(mockLoadBalancer);
    transports = TestUtils.captureTransports(mockTransportFactory);
    when(mockTransportFactory.getScheduledExecutorService())
        .thenReturn(timer.getScheduledExecutorService());
    when(executorPool.getObject()).thenReturn(executor.getScheduledExecutorService());
    when(oobExecutorPool.getObject()).thenReturn(oobExecutor.getScheduledExecutorService());
  }

  @After
  public void allPendingTasksAreRun() throws Exception {
    // The "never" verifications in the tests only hold up if all due tasks are done.
    // As for timer, although there may be scheduled tasks in a future time, since we don't test
    // any time-related behavior in this test suite, we only care the tasks that are due. This
    // would ignore any time-sensitive tasks, e.g., back-off and the idle timer.
    assertTrue(timer.getDueTasks() + " should be empty", timer.getDueTasks().isEmpty());
    assertEquals(executor.getPendingTasks() + " should be empty", 0, executor.numPendingTasks());
    if (channel != null) {
      channel.shutdownNow();
      channel = null;
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void idleModeDisabled() {
    createChannel(
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build(),
        NO_INTERCEPTOR);

    // In this test suite, the channel is always created with idle mode disabled.
    // No task is scheduled to enter idle mode
    assertEquals(0, timer.numPendingTasks());
    assertEquals(0, executor.numPendingTasks());
  }

  @Test
  public void immediateDeadlineExceeded() {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);
    ClientCall<String, Integer> call =
        channel.newCall(method, CallOptions.DEFAULT.withDeadlineAfter(0, TimeUnit.NANOSECONDS));
    call.start(mockCallListener, new Metadata());
    assertEquals(1, executor.runDueTasks());

    verify(mockCallListener).onClose(statusCaptor.capture(), any(Metadata.class));
    Status status = statusCaptor.getValue();
    assertSame(Status.DEADLINE_EXCEEDED.getCode(), status.getCode());
  }

  @Test
  public void shutdownWithNoTransportsEverCreated() {
    createChannel(
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build(),
        NO_INTERCEPTOR);
    verify(executorPool).getObject();
    verify(executorPool, never()).returnObject(anyObject());
    verifyNoMoreInteractions(mockTransportFactory);
    channel.shutdown();
    assertTrue(channel.isShutdown());
    assertTrue(channel.isTerminated());
    verify(executorPool).returnObject(executor.getScheduledExecutorService());
  }

  @Test
  public void channelzMembership() throws Exception {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);
    assertNotNull(channelz.getRootChannel(channel.getLogId().getId()));
    assertFalse(channelz.containsSubchannel(channel.getLogId()));
    channel.shutdownNow();
    channel.awaitTermination(5, TimeUnit.SECONDS);
    assertNull(channelz.getRootChannel(channel.getLogId().getId()));
    assertFalse(channelz.containsSubchannel(channel.getLogId()));
  }

  @Test
  public void channelzMembership_subchannel() throws Exception {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);
    assertNotNull(channelz.getRootChannel(channel.getLogId().getId()));

    AbstractSubchannel subchannel =
        (AbstractSubchannel) helper.createSubchannel(addressGroup, Attributes.EMPTY);
    // subchannels are not root channels
    assertNull(channelz.getRootChannel(subchannel.getInternalSubchannel().getLogId().getId()));
    assertTrue(channelz.containsSubchannel(subchannel.getInternalSubchannel().getLogId()));
    assertThat(getStats(channel).subchannels)
        .containsExactly(subchannel.getInternalSubchannel());

    subchannel.requestConnection();
    MockClientTransportInfo transportInfo = transports.poll();
    assertNotNull(transportInfo);
    assertTrue(channelz.containsSocket(transportInfo.transport.getLogId()));

    // terminate transport
    transportInfo.listener.transportTerminated();
    assertFalse(channelz.containsSocket(transportInfo.transport.getLogId()));

    // terminate subchannel
    assertTrue(channelz.containsSubchannel(subchannel.getInternalSubchannel().getLogId()));
    subchannel.shutdown();
    timer.forwardTime(ManagedChannelImpl.SUBCHANNEL_SHUTDOWN_DELAY_SECONDS, TimeUnit.SECONDS);
    timer.runDueTasks();
    assertFalse(channelz.containsSubchannel(subchannel.getInternalSubchannel().getLogId()));
    assertThat(getStats(channel).subchannels).isEmpty();

    // channel still appears
    assertNotNull(channelz.getRootChannel(channel.getLogId().getId()));
  }

  @Test
  public void channelzMembership_oob() throws Exception {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);
    OobChannel oob = (OobChannel) helper.createOobChannel(addressGroup, authority);
    // oob channels are not root channels
    assertNull(channelz.getRootChannel(oob.getLogId().getId()));
    assertTrue(channelz.containsSubchannel(oob.getLogId()));
    assertThat(getStats(channel).subchannels).containsExactly(oob);
    assertTrue(channelz.containsSubchannel(oob.getLogId()));

    AbstractSubchannel subchannel = (AbstractSubchannel) oob.getSubchannel();
    assertTrue(channelz.containsSubchannel(subchannel.getInternalSubchannel().getLogId()));
    assertThat(getStats(oob).subchannels)
        .containsExactly(subchannel.getInternalSubchannel());
    assertTrue(channelz.containsSubchannel(subchannel.getInternalSubchannel().getLogId()));

    oob.getSubchannel().requestConnection();
    MockClientTransportInfo transportInfo = transports.poll();
    assertNotNull(transportInfo);
    assertTrue(channelz.containsSocket(transportInfo.transport.getLogId()));

    // terminate transport
    transportInfo.listener.transportTerminated();
    assertFalse(channelz.containsSocket(transportInfo.transport.getLogId()));

    // terminate oobchannel
    oob.shutdown();
    assertFalse(channelz.containsSubchannel(oob.getLogId()));
    assertThat(getStats(channel).subchannels).isEmpty();
    assertFalse(channelz.containsSubchannel(subchannel.getInternalSubchannel().getLogId()));

    // channel still appears
    assertNotNull(channelz.getRootChannel(channel.getLogId().getId()));
  }

  @Test
  public void callsAndShutdown() {
    subtestCallsAndShutdown(false, false);
  }

  @Test
  public void callsAndShutdownNow() {
    subtestCallsAndShutdown(true, false);
  }

  /** Make sure shutdownNow() after shutdown() has an effect. */
  @Test
  public void callsAndShutdownAndShutdownNow() {
    subtestCallsAndShutdown(false, true);
  }

  private void subtestCallsAndShutdown(boolean shutdownNow, boolean shutdownNowAfterShutdown) {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).build();
    createChannel(nameResolverFactory, NO_INTERCEPTOR);
    verify(executorPool).getObject();
    ClientStream mockStream = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    Metadata headers = new Metadata();
    Metadata headers2 = new Metadata();

    // Configure the picker so that first RPC goes to delayed transport, and second RPC goes to
    // real transport.
    Subchannel subchannel = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    subchannel.requestConnection();
    verify(mockTransportFactory).newClientTransport(
        any(SocketAddress.class), any(String.class), any(String.class), any(ProxyParameters.class));
    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    verify(mockTransport).start(any(ManagedClientTransport.Listener.class));
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    when(mockTransport.newStream(same(method), same(headers), same(CallOptions.DEFAULT)))
        .thenReturn(mockStream);
    when(mockTransport.newStream(same(method), same(headers2), same(CallOptions.DEFAULT)))
        .thenReturn(mockStream2);
    transportListener.transportReady();
    when(mockPicker.pickSubchannel(
        new PickSubchannelArgsImpl(method, headers, CallOptions.DEFAULT))).thenReturn(
        PickResult.withNoResult());
    when(mockPicker.pickSubchannel(
        new PickSubchannelArgsImpl(method, headers2, CallOptions.DEFAULT))).thenReturn(
        PickResult.withSubchannel(subchannel));
    helper.updateBalancingState(READY, mockPicker);

    // First RPC, will be pending
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    verify(mockTransportFactory).newClientTransport(
        any(SocketAddress.class), any(String.class), any(String.class), any(ProxyParameters.class));
    call.start(mockCallListener, headers);

    verify(mockTransport, never())
        .newStream(same(method), same(headers), same(CallOptions.DEFAULT));

    // Second RPC, will be assigned to the real transport
    ClientCall<String, Integer> call2 = channel.newCall(method, CallOptions.DEFAULT);
    call2.start(mockCallListener2, headers2);
    verify(mockTransport).newStream(same(method), same(headers2), same(CallOptions.DEFAULT));
    verify(mockTransport).newStream(same(method), same(headers2), same(CallOptions.DEFAULT));
    verify(mockStream2).start(any(ClientStreamListener.class));

    // Shutdown
    if (shutdownNow) {
      channel.shutdownNow();
    } else {
      channel.shutdown();
      if (shutdownNowAfterShutdown) {
        channel.shutdownNow();
        shutdownNow = true;
      }
    }
    assertTrue(channel.isShutdown());
    assertFalse(channel.isTerminated());
    assertEquals(1, nameResolverFactory.resolvers.size());
    verify(mockLoadBalancerFactory).newLoadBalancer(any(Helper.class));

    // Further calls should fail without going to the transport
    ClientCall<String, Integer> call3 = channel.newCall(method, CallOptions.DEFAULT);
    call3.start(mockCallListener3, headers2);
    timer.runDueTasks();
    executor.runDueTasks();

    verify(mockCallListener3).onClose(statusCaptor.capture(), any(Metadata.class));
    assertSame(Status.Code.UNAVAILABLE, statusCaptor.getValue().getCode());

    if (shutdownNow) {
      // LoadBalancer and NameResolver are shut down as soon as delayed transport is terminated.
      verify(mockLoadBalancer).shutdown();
      assertTrue(nameResolverFactory.resolvers.get(0).shutdown);
      // call should have been aborted by delayed transport
      executor.runDueTasks();
      verify(mockCallListener).onClose(same(ManagedChannelImpl.SHUTDOWN_NOW_STATUS),
          any(Metadata.class));
    } else {
      // LoadBalancer and NameResolver are still running.
      verify(mockLoadBalancer, never()).shutdown();
      assertFalse(nameResolverFactory.resolvers.get(0).shutdown);
      // call and call2 are still alive, and can still be assigned to a real transport
      SubchannelPicker picker2 = mock(SubchannelPicker.class);
      when(picker2.pickSubchannel(new PickSubchannelArgsImpl(method, headers, CallOptions.DEFAULT)))
          .thenReturn(PickResult.withSubchannel(subchannel));
      helper.updateBalancingState(READY, picker2);
      executor.runDueTasks();
      verify(mockTransport).newStream(same(method), same(headers), same(CallOptions.DEFAULT));
      verify(mockStream).start(any(ClientStreamListener.class));
    }

    // After call is moved out of delayed transport, LoadBalancer, NameResolver and the transports
    // will be shutdown.
    verify(mockLoadBalancer).shutdown();
    assertTrue(nameResolverFactory.resolvers.get(0).shutdown);

    if (shutdownNow) {
      // Channel shutdownNow() all subchannels after shutting down LoadBalancer
      verify(mockTransport).shutdownNow(ManagedChannelImpl.SHUTDOWN_NOW_STATUS);
    } else {
      verify(mockTransport, never()).shutdownNow(any(Status.class));
    }
    // LoadBalancer should shutdown the subchannel
    subchannel.shutdown();
    if (shutdownNow) {
      verify(mockTransport).shutdown(same(ManagedChannelImpl.SHUTDOWN_NOW_STATUS));
    } else {
      verify(mockTransport).shutdown(same(ManagedChannelImpl.SHUTDOWN_STATUS));
    }

    // Killing the remaining real transport will terminate the channel
    transportListener.transportShutdown(Status.UNAVAILABLE);
    assertFalse(channel.isTerminated());
    verify(executorPool, never()).returnObject(anyObject());
    transportListener.transportTerminated();
    assertTrue(channel.isTerminated());
    verify(executorPool).returnObject(executor.getScheduledExecutorService());
    verifyNoMoreInteractions(oobExecutorPool);

    verify(mockTransportFactory).newClientTransport(
        any(SocketAddress.class), any(String.class), any(String.class), any(ProxyParameters.class));
    verify(mockTransportFactory).close();
    verify(mockTransport, atLeast(0)).getLogId();
    verifyNoMoreInteractions(mockTransport);
  }

  @Test
  public void noMoreCallbackAfterLoadBalancerShutdown() {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build();
    Status resolutionError = Status.UNAVAILABLE.withDescription("Resolution failed");
    createChannel(nameResolverFactory, NO_INTERCEPTOR);

    FakeNameResolverFactory.FakeNameResolver resolver = nameResolverFactory.resolvers.get(0);
    verify(mockLoadBalancerFactory).newLoadBalancer(any(Helper.class));
    verify(mockLoadBalancer).handleResolvedAddressGroups(
        eq(Arrays.asList(addressGroup)), eq(Attributes.EMPTY));

    Subchannel subchannel1 = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    Subchannel subchannel2 = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    subchannel1.requestConnection();
    subchannel2.requestConnection();
    verify(mockTransportFactory, times(2)).newClientTransport(
        any(SocketAddress.class), any(String.class), any(String.class), any(ProxyParameters.class));
    MockClientTransportInfo transportInfo1 = transports.poll();
    MockClientTransportInfo transportInfo2 = transports.poll();

    // LoadBalancer receives all sorts of callbacks
    transportInfo1.listener.transportReady();
    verify(mockLoadBalancer, times(2))
        .handleSubchannelState(same(subchannel1), stateInfoCaptor.capture());
    assertSame(CONNECTING, stateInfoCaptor.getAllValues().get(0).getState());
    assertSame(READY, stateInfoCaptor.getAllValues().get(1).getState());

    verify(mockLoadBalancer)
        .handleSubchannelState(same(subchannel2), stateInfoCaptor.capture());
    assertSame(CONNECTING, stateInfoCaptor.getValue().getState());

    resolver.listener.onError(resolutionError);
    verify(mockLoadBalancer).handleNameResolutionError(resolutionError);

    verifyNoMoreInteractions(mockLoadBalancer);

    channel.shutdown();
    verify(mockLoadBalancer).shutdown();

    // No more callback should be delivered to LoadBalancer after it's shut down
    transportInfo2.listener.transportReady();
    resolver.listener.onError(resolutionError);
    resolver.resolved();
    verifyNoMoreInteractions(mockLoadBalancer);
  }

  @Test
  public void interceptor() throws Exception {
    final AtomicLong atomic = new AtomicLong();
    ClientInterceptor interceptor = new ClientInterceptor() {
      @Override
      public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> interceptCall(
          MethodDescriptor<RequestT, ResponseT> method, CallOptions callOptions,
          Channel next) {
        atomic.set(1);
        return next.newCall(method, callOptions);
      }
    };
    createChannel(
        new FakeNameResolverFactory.Builder(expectedUri).build(), Arrays.asList(interceptor));
    assertNotNull(channel.newCall(method, CallOptions.DEFAULT));
    assertEquals(1, atomic.get());
  }

  @Test
  public void callOptionsExecutor() {
    Metadata headers = new Metadata();
    ClientStream mockStream = mock(ClientStream.class);
    FakeClock callExecutor = new FakeClock();
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);

    // Start a call with a call executor
    CallOptions options =
        CallOptions.DEFAULT.withExecutor(callExecutor.getScheduledExecutorService());
    ClientCall<String, Integer> call = channel.newCall(method, options);
    call.start(mockCallListener, headers);

    // Make the transport available
    Subchannel subchannel = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    verify(mockTransportFactory, never()).newClientTransport(
        any(SocketAddress.class), any(String.class), any(String.class), any(ProxyParameters.class));
    subchannel.requestConnection();
    verify(mockTransportFactory).newClientTransport(
        any(SocketAddress.class), any(String.class), any(String.class), any(ProxyParameters.class));
    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    when(mockTransport.newStream(same(method), same(headers), any(CallOptions.class)))
        .thenReturn(mockStream);
    transportListener.transportReady();
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    assertEquals(0, callExecutor.numPendingTasks());
    helper.updateBalancingState(READY, mockPicker);

    // Real streams are started in the call executor if they were previously buffered.
    assertEquals(1, callExecutor.runDueTasks());
    verify(mockTransport).newStream(same(method), same(headers), same(options));
    verify(mockStream).start(streamListenerCaptor.capture());

    // Call listener callbacks are also run in the call executor
    ClientStreamListener streamListener = streamListenerCaptor.getValue();
    Metadata trailers = new Metadata();
    assertEquals(0, callExecutor.numPendingTasks());
    streamListener.closed(Status.CANCELLED, trailers);
    verify(mockCallListener, never()).onClose(same(Status.CANCELLED), same(trailers));
    assertEquals(1, callExecutor.runDueTasks());
    verify(mockCallListener).onClose(same(Status.CANCELLED), same(trailers));


    transportListener.transportShutdown(Status.UNAVAILABLE);
    transportListener.transportTerminated();

    // Clean up as much as possible to allow the channel to terminate.
    subchannel.shutdown();
    timer.forwardNanos(
        TimeUnit.SECONDS.toNanos(ManagedChannelImpl.SUBCHANNEL_SHUTDOWN_DELAY_SECONDS));
  }

  @Test
  public void nameResolutionFailed() {
    Status error = Status.UNAVAILABLE.withCause(new Throwable("fake name resolution error"));
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .setError(error)
            .build();
    // Name resolution is started as soon as channel is created.
    createChannel(nameResolverFactory, NO_INTERCEPTOR);
    FakeNameResolverFactory.FakeNameResolver resolver = nameResolverFactory.resolvers.get(0);
    verify(mockLoadBalancer).handleNameResolutionError(same(error));
    assertEquals(1, timer.numPendingTasks(NAME_RESOLVER_REFRESH_TASK_FILTER));

    timer.forwardNanos(RECONNECT_BACKOFF_INTERVAL_NANOS - 1);
    assertEquals(0, resolver.refreshCalled);

    timer.forwardNanos(1);
    assertEquals(1, resolver.refreshCalled);
    verify(mockLoadBalancer, times(2)).handleNameResolutionError(same(error));

    // Verify an additional name resolution failure does not schedule another timer
    resolver.refresh();
    verify(mockLoadBalancer, times(3)).handleNameResolutionError(same(error));
    assertEquals(1, timer.numPendingTasks(NAME_RESOLVER_REFRESH_TASK_FILTER));

    // Allow the next refresh attempt to succeed
    resolver.error = null;

    // For the second attempt, the backoff should occur at RECONNECT_BACKOFF_INTERVAL_NANOS * 2
    timer.forwardNanos(RECONNECT_BACKOFF_INTERVAL_NANOS * 2 - 1);
    assertEquals(2, resolver.refreshCalled);
    timer.forwardNanos(1);
    assertEquals(3, resolver.refreshCalled);
    assertEquals(0, timer.numPendingTasks());

    // Verify that the successful resolution reset the backoff policy
    resolver.listener.onError(error);
    timer.forwardNanos(RECONNECT_BACKOFF_INTERVAL_NANOS - 1);
    assertEquals(3, resolver.refreshCalled);
    timer.forwardNanos(1);
    assertEquals(4, resolver.refreshCalled);
    assertEquals(0, timer.numPendingTasks());
  }

  @Test
  public void nameResolutionFailed_delayedTransportShutdownCancelsBackoff() {
    Status error = Status.UNAVAILABLE.withCause(new Throwable("fake name resolution error"));

    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).setError(error).build();
    // Name resolution is started as soon as channel is created.
    createChannel(nameResolverFactory, NO_INTERCEPTOR);
    verify(mockLoadBalancer).handleNameResolutionError(same(error));

    FakeClock.ScheduledTask nameResolverBackoff = getNameResolverRefresh();
    assertNotNull(nameResolverBackoff);
    assertFalse(nameResolverBackoff.isCancelled());

    // Add a pending call to the delayed transport
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    Metadata headers = new Metadata();
    call.start(mockCallListener, headers);

    // The pending call on the delayed transport stops the name resolver backoff from cancelling
    channel.shutdown();
    assertFalse(nameResolverBackoff.isCancelled());

    // Notify that a subchannel is ready, which drains the delayed transport
    SubchannelPicker picker = mock(SubchannelPicker.class);
    Status status = Status.UNAVAILABLE.withDescription("for test");
    when(picker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withDrop(status));
    helper.updateBalancingState(READY, picker);
    executor.runDueTasks();
    verify(mockCallListener).onClose(same(status), any(Metadata.class));

    assertTrue(nameResolverBackoff.isCancelled());
  }

  @Test
  public void nameResolverReturnsEmptySubLists() {
    String errorDescription = "NameResolver returned an empty list";

    // Pass a FakeNameResolverFactory with an empty list
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);

    // LoadBalancer received the error
    verify(mockLoadBalancerFactory).newLoadBalancer(any(Helper.class));
    verify(mockLoadBalancer).handleNameResolutionError(statusCaptor.capture());
    Status status = statusCaptor.getValue();
    assertSame(Status.Code.UNAVAILABLE, status.getCode());
    assertEquals(errorDescription, status.getDescription());
  }

  @Test
  public void loadBalancerThrowsInHandleResolvedAddresses() {
    RuntimeException ex = new RuntimeException("simulated");
    // Delay the success of name resolution until allResolved() is called
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setResolvedAtStart(false)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build();
    createChannel(nameResolverFactory, NO_INTERCEPTOR);

    verify(mockLoadBalancerFactory).newLoadBalancer(any(Helper.class));
    doThrow(ex).when(mockLoadBalancer).handleResolvedAddressGroups(
        Matchers.<List<EquivalentAddressGroup>>anyObject(), any(Attributes.class));

    // NameResolver returns addresses.
    nameResolverFactory.allResolved();

    // Exception thrown from balancer is caught by ChannelExecutor, making channel enter panic mode.
    verifyPanicMode(ex);
  }

  @Test
  public void nameResolvedAfterChannelShutdown() {
    // Delay the success of name resolution until allResolved() is called.
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).setResolvedAtStart(false).build();
    createChannel(nameResolverFactory, NO_INTERCEPTOR);

    channel.shutdown();

    assertTrue(channel.isShutdown());
    assertTrue(channel.isTerminated());
    verify(mockLoadBalancer).shutdown();
    // Name resolved after the channel is shut down, which is possible if the name resolution takes
    // time and is not cancellable. The resolved address will be dropped.
    nameResolverFactory.allResolved();
    verifyNoMoreInteractions(mockLoadBalancer);
  }

  /**
   * Verify that if the first resolved address points to a server that cannot be connected, the call
   * will end up with the second address which works.
   */
  @Test
  public void firstResolvedServerFailedToConnect() throws Exception {
    final SocketAddress goodAddress = new SocketAddress() {
        @Override public String toString() {
          return "goodAddress";
        }
      };
    final SocketAddress badAddress = new SocketAddress() {
        @Override public String toString() {
          return "badAddress";
        }
      };
    InOrder inOrder = inOrder(mockLoadBalancer);

    List<SocketAddress> resolvedAddrs = Arrays.asList(badAddress, goodAddress);
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(resolvedAddrs)))
            .build();
    createChannel(nameResolverFactory, NO_INTERCEPTOR);

    // Start the call
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    Metadata headers = new Metadata();
    call.start(mockCallListener, headers);
    executor.runDueTasks();

    // Simulate name resolution results
    EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(resolvedAddrs);
    inOrder.verify(mockLoadBalancer).handleResolvedAddressGroups(
        eq(Arrays.asList(addressGroup)), eq(Attributes.EMPTY));
    Subchannel subchannel = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    subchannel.requestConnection();
    inOrder.verify(mockLoadBalancer).handleSubchannelState(
        same(subchannel), stateInfoCaptor.capture());
    assertEquals(CONNECTING, stateInfoCaptor.getValue().getState());

    // The channel will starts with the first address (badAddress)
    verify(mockTransportFactory)
        .newClientTransport(same(badAddress), any(String.class), any(String.class),
            any(ProxyParameters.class));
    verify(mockTransportFactory, times(0))
          .newClientTransport(same(goodAddress), any(String.class), any(String.class),
              any(ProxyParameters.class));

    MockClientTransportInfo badTransportInfo = transports.poll();
    // Which failed to connect
    badTransportInfo.listener.transportShutdown(Status.UNAVAILABLE);
    inOrder.verifyNoMoreInteractions();

    // The channel then try the second address (goodAddress)
    verify(mockTransportFactory)
          .newClientTransport(same(goodAddress), any(String.class), any(String.class),
              any(ProxyParameters.class));
    MockClientTransportInfo goodTransportInfo = transports.poll();
    when(goodTransportInfo.transport.newStream(
            any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class)))
        .thenReturn(mock(ClientStream.class));

    goodTransportInfo.listener.transportReady();
    inOrder.verify(mockLoadBalancer).handleSubchannelState(
        same(subchannel), stateInfoCaptor.capture());
    assertEquals(READY, stateInfoCaptor.getValue().getState());

    // A typical LoadBalancer will call this once the subchannel becomes READY
    helper.updateBalancingState(READY, mockPicker);
    // Delayed transport uses the app executor to create real streams.
    executor.runDueTasks();

    verify(goodTransportInfo.transport).newStream(same(method), same(headers),
        same(CallOptions.DEFAULT));
    // The bad transport was never used.
    verify(badTransportInfo.transport, times(0)).newStream(any(MethodDescriptor.class),
        any(Metadata.class), any(CallOptions.class));
  }

  @Test
  public void failFastRpcFailFromErrorFromBalancer() {
    subtestFailRpcFromBalancer(false, false, true);
  }

  @Test
  public void failFastRpcFailFromDropFromBalancer() {
    subtestFailRpcFromBalancer(false, true, true);
  }

  @Test
  public void waitForReadyRpcImmuneFromErrorFromBalancer() {
    subtestFailRpcFromBalancer(true, false, false);
  }

  @Test
  public void waitForReadyRpcFailFromDropFromBalancer() {
    subtestFailRpcFromBalancer(true, true, true);
  }

  private void subtestFailRpcFromBalancer(boolean waitForReady, boolean drop, boolean shouldFail) {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);

    // This call will be buffered by the channel, thus involve delayed transport
    CallOptions callOptions = CallOptions.DEFAULT;
    if (waitForReady) {
      callOptions = callOptions.withWaitForReady();
    } else {
      callOptions = callOptions.withoutWaitForReady();
    }
    ClientCall<String, Integer> call1 = channel.newCall(method, callOptions);
    call1.start(mockCallListener, new Metadata());

    SubchannelPicker picker = mock(SubchannelPicker.class);
    Status status = Status.UNAVAILABLE.withDescription("for test");

    when(picker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(drop ? PickResult.withDrop(status) : PickResult.withError(status));
    helper.updateBalancingState(READY, picker);

    executor.runDueTasks();
    if (shouldFail) {
      verify(mockCallListener).onClose(same(status), any(Metadata.class));
    } else {
      verifyZeroInteractions(mockCallListener);
    }

    // This call doesn't involve delayed transport
    ClientCall<String, Integer> call2 = channel.newCall(method, callOptions);
    call2.start(mockCallListener2, new Metadata());

    executor.runDueTasks();
    if (shouldFail) {
      verify(mockCallListener2).onClose(same(status), any(Metadata.class));
    } else {
      verifyZeroInteractions(mockCallListener2);
    }
  }

  /**
   * Verify that if all resolved addresses failed to connect, a fail-fast call will fail, while a
   * wait-for-ready call will still be buffered.
   */
  @Test
  public void allServersFailedToConnect() throws Exception {
    final SocketAddress addr1 = new SocketAddress() {
        @Override public String toString() {
          return "addr1";
        }
      };
    final SocketAddress addr2 = new SocketAddress() {
        @Override public String toString() {
          return "addr2";
        }
      };
    InOrder inOrder = inOrder(mockLoadBalancer);

    List<SocketAddress> resolvedAddrs = Arrays.asList(addr1, addr2);

    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(resolvedAddrs)))
            .build();
    createChannel(nameResolverFactory, NO_INTERCEPTOR);

    // Start a wait-for-ready call
    ClientCall<String, Integer> call =
        channel.newCall(method, CallOptions.DEFAULT.withWaitForReady());
    Metadata headers = new Metadata();
    call.start(mockCallListener, headers);
    // ... and a fail-fast call
    ClientCall<String, Integer> call2 =
        channel.newCall(method, CallOptions.DEFAULT.withoutWaitForReady());
    call2.start(mockCallListener2, headers);
    executor.runDueTasks();

    // Simulate name resolution results
    EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(resolvedAddrs);
    inOrder.verify(mockLoadBalancer).handleResolvedAddressGroups(
        eq(Arrays.asList(addressGroup)), eq(Attributes.EMPTY));
    Subchannel subchannel = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    subchannel.requestConnection();

    inOrder.verify(mockLoadBalancer).handleSubchannelState(
        same(subchannel), stateInfoCaptor.capture());
    assertEquals(CONNECTING, stateInfoCaptor.getValue().getState());

    // Connecting to server1, which will fail
    verify(mockTransportFactory)
        .newClientTransport(same(addr1), any(String.class), any(String.class),
            any(ProxyParameters.class));
    verify(mockTransportFactory, times(0))
        .newClientTransport(same(addr2), any(String.class), any(String.class),
            any(ProxyParameters.class));
    MockClientTransportInfo transportInfo1 = transports.poll();
    transportInfo1.listener.transportShutdown(Status.UNAVAILABLE);

    // Connecting to server2, which will fail too
    verify(mockTransportFactory)
        .newClientTransport(same(addr2), any(String.class), any(String.class),
            any(ProxyParameters.class));
    MockClientTransportInfo transportInfo2 = transports.poll();
    Status server2Error = Status.UNAVAILABLE.withDescription("Server2 failed to connect");
    transportInfo2.listener.transportShutdown(server2Error);

    // ... which makes the subchannel enter TRANSIENT_FAILURE. The last error Status is propagated
    // to LoadBalancer.
    inOrder.verify(mockLoadBalancer).handleSubchannelState(
        same(subchannel), stateInfoCaptor.capture());
    assertEquals(TRANSIENT_FAILURE, stateInfoCaptor.getValue().getState());
    assertSame(server2Error, stateInfoCaptor.getValue().getStatus());

    // A typical LoadBalancer would create a picker with error
    SubchannelPicker picker2 = mock(SubchannelPicker.class);
    when(picker2.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withError(server2Error));
    helper.updateBalancingState(TRANSIENT_FAILURE, picker2);
    executor.runDueTasks();

    // ... which fails the fail-fast call
    verify(mockCallListener2).onClose(same(server2Error), any(Metadata.class));
    // ... while the wait-for-ready call stays
    verifyNoMoreInteractions(mockCallListener);
    // No real stream was ever created
    verify(transportInfo1.transport, times(0))
        .newStream(any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class));
    verify(transportInfo2.transport, times(0))
        .newStream(any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class));
  }

  @Test
  public void subchannels() {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);

    // createSubchannel() always return a new Subchannel
    Attributes attrs1 = Attributes.newBuilder().set(SUBCHANNEL_ATTR_KEY, "attr1").build();
    Attributes attrs2 = Attributes.newBuilder().set(SUBCHANNEL_ATTR_KEY, "attr2").build();
    Subchannel sub1 = helper.createSubchannel(addressGroup, attrs1);
    Subchannel sub2 = helper.createSubchannel(addressGroup, attrs2);
    assertNotSame(sub1, sub2);
    assertNotSame(attrs1, attrs2);
    assertSame(attrs1, sub1.getAttributes());
    assertSame(attrs2, sub2.getAttributes());
    assertSame(addressGroup, sub1.getAddresses());
    assertSame(addressGroup, sub2.getAddresses());

    // requestConnection()
    verify(mockTransportFactory, never()).newClientTransport(
        any(SocketAddress.class), any(String.class), any(String.class), any(ProxyParameters.class));
    sub1.requestConnection();
    verify(mockTransportFactory).newClientTransport(socketAddress, authority, userAgent, noProxy);
    MockClientTransportInfo transportInfo1 = transports.poll();
    assertNotNull(transportInfo1);

    sub2.requestConnection();
    verify(mockTransportFactory, times(2)).newClientTransport(socketAddress, authority, userAgent,
        noProxy);
    MockClientTransportInfo transportInfo2 = transports.poll();
    assertNotNull(transportInfo2);

    sub1.requestConnection();
    sub2.requestConnection();
    verify(mockTransportFactory, times(2)).newClientTransport(socketAddress, authority, userAgent,
        noProxy);

    // shutdown() has a delay
    sub1.shutdown();
    timer.forwardTime(ManagedChannelImpl.SUBCHANNEL_SHUTDOWN_DELAY_SECONDS - 1, TimeUnit.SECONDS);
    sub1.shutdown();
    verify(transportInfo1.transport, never()).shutdown(any(Status.class));
    timer.forwardTime(1, TimeUnit.SECONDS);
    verify(transportInfo1.transport).shutdown(same(ManagedChannelImpl.SUBCHANNEL_SHUTDOWN_STATUS));

    // ... but not after Channel is terminating
    verify(mockLoadBalancer, never()).shutdown();
    channel.shutdown();
    verify(mockLoadBalancer).shutdown();
    verify(transportInfo2.transport, never()).shutdown(any(Status.class));

    sub2.shutdown();
    verify(transportInfo2.transport).shutdown(same(ManagedChannelImpl.SHUTDOWN_STATUS));

    // Cleanup
    transportInfo1.listener.transportShutdown(Status.UNAVAILABLE);
    transportInfo1.listener.transportTerminated();
    transportInfo2.listener.transportShutdown(Status.UNAVAILABLE);
    transportInfo2.listener.transportTerminated();
    timer.forwardTime(ManagedChannelImpl.SUBCHANNEL_SHUTDOWN_DELAY_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  public void subchannelsWhenChannelShutdownNow() {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);
    Subchannel sub1 = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    Subchannel sub2 = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    sub1.requestConnection();
    sub2.requestConnection();

    assertEquals(2, transports.size());
    MockClientTransportInfo ti1 = transports.poll();
    MockClientTransportInfo ti2 = transports.poll();

    ti1.listener.transportReady();
    ti2.listener.transportReady();

    channel.shutdownNow();
    verify(ti1.transport).shutdownNow(any(Status.class));
    verify(ti2.transport).shutdownNow(any(Status.class));

    ti1.listener.transportShutdown(Status.UNAVAILABLE.withDescription("shutdown now"));
    ti2.listener.transportShutdown(Status.UNAVAILABLE.withDescription("shutdown now"));
    ti1.listener.transportTerminated();

    assertFalse(channel.isTerminated());
    ti2.listener.transportTerminated();
    assertTrue(channel.isTerminated());
  }

  @Test
  public void subchannelsNoConnectionShutdown() {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);
    Subchannel sub1 = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    Subchannel sub2 = helper.createSubchannel(addressGroup, Attributes.EMPTY);

    channel.shutdown();
    verify(mockLoadBalancer).shutdown();
    sub1.shutdown();
    assertFalse(channel.isTerminated());
    sub2.shutdown();
    assertTrue(channel.isTerminated());
    verify(mockTransportFactory, never()).newClientTransport(any(SocketAddress.class), anyString(),
        anyString(), any(ProxyParameters.class));
  }

  @Test
  public void subchannelsNoConnectionShutdownNow() {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);
    helper.createSubchannel(addressGroup, Attributes.EMPTY);
    helper.createSubchannel(addressGroup, Attributes.EMPTY);
    channel.shutdownNow();

    verify(mockLoadBalancer).shutdown();
    // Channel's shutdownNow() will call shutdownNow() on all subchannels and oobchannels.
    // Therefore, channel is terminated without relying on LoadBalancer to shutdown subchannels.
    assertTrue(channel.isTerminated());
    verify(mockTransportFactory, never()).newClientTransport(any(SocketAddress.class), anyString(),
        anyString(), any(ProxyParameters.class));
  }

  @Test
  public void oobchannels() {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);

    ManagedChannel oob1 = helper.createOobChannel(addressGroup, "oob1authority");
    ManagedChannel oob2 = helper.createOobChannel(addressGroup, "oob2authority");
    verify(oobExecutorPool, times(2)).getObject();

    assertEquals("oob1authority", oob1.authority());
    assertEquals("oob2authority", oob2.authority());

    // OOB channels create connections lazily.  A new call will initiate the connection.
    Metadata headers = new Metadata();
    ClientCall<String, Integer> call = oob1.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, headers);
    verify(mockTransportFactory).newClientTransport(socketAddress, "oob1authority", userAgent,
        noProxy);
    MockClientTransportInfo transportInfo = transports.poll();
    assertNotNull(transportInfo);

    assertEquals(0, oobExecutor.numPendingTasks());
    transportInfo.listener.transportReady();
    assertEquals(1, oobExecutor.runDueTasks());
    verify(transportInfo.transport).newStream(same(method), same(headers),
        same(CallOptions.DEFAULT));

    // The transport goes away
    transportInfo.listener.transportShutdown(Status.UNAVAILABLE);
    transportInfo.listener.transportTerminated();

    // A new call will trigger a new transport
    ClientCall<String, Integer> call2 = oob1.newCall(method, CallOptions.DEFAULT);
    call2.start(mockCallListener2, headers);
    ClientCall<String, Integer> call3 =
        oob1.newCall(method, CallOptions.DEFAULT.withWaitForReady());
    call3.start(mockCallListener3, headers);
    verify(mockTransportFactory, times(2)).newClientTransport(
        socketAddress, "oob1authority", userAgent, noProxy);
    transportInfo = transports.poll();
    assertNotNull(transportInfo);

    // This transport fails
    Status transportError = Status.UNAVAILABLE.withDescription("Connection refused");
    assertEquals(0, oobExecutor.numPendingTasks());
    transportInfo.listener.transportShutdown(transportError);
    assertTrue(oobExecutor.runDueTasks() > 0);

    // Fail-fast RPC will fail, while wait-for-ready RPC will still be pending
    verify(mockCallListener2).onClose(same(transportError), any(Metadata.class));
    verify(mockCallListener3, never()).onClose(any(Status.class), any(Metadata.class));

    // Shutdown
    assertFalse(oob1.isShutdown());
    assertFalse(oob2.isShutdown());
    oob1.shutdown();
    verify(oobExecutorPool, never()).returnObject(anyObject());
    oob2.shutdownNow();
    assertTrue(oob1.isShutdown());
    assertTrue(oob2.isShutdown());
    assertTrue(oob2.isTerminated());
    verify(oobExecutorPool).returnObject(oobExecutor.getScheduledExecutorService());

    // New RPCs will be rejected.
    assertEquals(0, oobExecutor.numPendingTasks());
    ClientCall<String, Integer> call4 = oob1.newCall(method, CallOptions.DEFAULT);
    ClientCall<String, Integer> call5 = oob2.newCall(method, CallOptions.DEFAULT);
    call4.start(mockCallListener4, headers);
    call5.start(mockCallListener5, headers);
    assertTrue(oobExecutor.runDueTasks() > 0);
    verify(mockCallListener4).onClose(statusCaptor.capture(), any(Metadata.class));
    Status status4 = statusCaptor.getValue();
    assertEquals(Status.Code.UNAVAILABLE, status4.getCode());
    verify(mockCallListener5).onClose(statusCaptor.capture(), any(Metadata.class));
    Status status5 = statusCaptor.getValue();
    assertEquals(Status.Code.UNAVAILABLE, status5.getCode());

    // The pending RPC will still be pending
    verify(mockCallListener3, never()).onClose(any(Status.class), any(Metadata.class));

    // This will shutdownNow() the delayed transport, terminating the pending RPC
    assertEquals(0, oobExecutor.numPendingTasks());
    oob1.shutdownNow();
    assertTrue(oobExecutor.runDueTasks() > 0);
    verify(mockCallListener3).onClose(any(Status.class), any(Metadata.class));

    // Shut down the channel, and it will not terminated because OOB channel has not.
    channel.shutdown();
    assertFalse(channel.isTerminated());
    // Delayed transport has already terminated.  Terminating the transport terminates the
    // subchannel, which in turn terimates the OOB channel, which terminates the channel.
    assertFalse(oob1.isTerminated());
    verify(oobExecutorPool).returnObject(oobExecutor.getScheduledExecutorService());
    transportInfo.listener.transportTerminated();
    assertTrue(oob1.isTerminated());
    assertTrue(channel.isTerminated());
    verify(oobExecutorPool, times(2)).returnObject(oobExecutor.getScheduledExecutorService());
  }

  @Test
  public void oobChannelsWhenChannelShutdownNow() {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);
    ManagedChannel oob1 = helper.createOobChannel(addressGroup, "oob1Authority");
    ManagedChannel oob2 = helper.createOobChannel(addressGroup, "oob2Authority");

    oob1.newCall(method, CallOptions.DEFAULT).start(mockCallListener, new Metadata());
    oob2.newCall(method, CallOptions.DEFAULT).start(mockCallListener2, new Metadata());

    assertEquals(2, transports.size());
    MockClientTransportInfo ti1 = transports.poll();
    MockClientTransportInfo ti2 = transports.poll();

    ti1.listener.transportReady();
    ti2.listener.transportReady();

    channel.shutdownNow();
    verify(ti1.transport).shutdownNow(any(Status.class));
    verify(ti2.transport).shutdownNow(any(Status.class));

    ti1.listener.transportShutdown(Status.UNAVAILABLE.withDescription("shutdown now"));
    ti2.listener.transportShutdown(Status.UNAVAILABLE.withDescription("shutdown now"));
    ti1.listener.transportTerminated();

    assertFalse(channel.isTerminated());
    ti2.listener.transportTerminated();
    assertTrue(channel.isTerminated());
  }

  @Test
  public void oobChannelsNoConnectionShutdown() {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);
    ManagedChannel oob1 = helper.createOobChannel(addressGroup, "oob1Authority");
    ManagedChannel oob2 = helper.createOobChannel(addressGroup, "oob2Authority");
    channel.shutdown();

    verify(mockLoadBalancer).shutdown();
    oob1.shutdown();
    assertTrue(oob1.isTerminated());
    assertFalse(channel.isTerminated());
    oob2.shutdown();
    assertTrue(oob2.isTerminated());
    assertTrue(channel.isTerminated());
    verify(mockTransportFactory, never()).newClientTransport(any(SocketAddress.class), anyString(),
        anyString(), any(ProxyParameters.class));
  }

  @Test
  public void oobChannelsNoConnectionShutdownNow() {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);
    helper.createOobChannel(addressGroup, "oob1Authority");
    helper.createOobChannel(addressGroup, "oob2Authority");
    channel.shutdownNow();

    verify(mockLoadBalancer).shutdown();
    assertTrue(channel.isTerminated());
    // Channel's shutdownNow() will call shutdownNow() on all subchannels and oobchannels.
    // Therefore, channel is terminated without relying on LoadBalancer to shutdown oobchannels.
    verify(mockTransportFactory, never()).newClientTransport(any(SocketAddress.class), anyString(),
        anyString(), any(ProxyParameters.class));
  }

  @Test
  public void refreshNameResolutionWhenSubchannelConnectionFailed() {
    subtestRefreshNameResolutionWhenConnectionFailed(false);
  }

  @Test
  public void refreshNameResolutionWhenOobChannelConnectionFailed() {
    subtestRefreshNameResolutionWhenConnectionFailed(true);
  }

  private void subtestRefreshNameResolutionWhenConnectionFailed(boolean isOobChannel) {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build();
    createChannel(nameResolverFactory, NO_INTERCEPTOR);
    FakeNameResolverFactory.FakeNameResolver resolver = nameResolverFactory.resolvers.get(0);

    if (isOobChannel) {
      OobChannel oobChannel = (OobChannel) helper.createOobChannel(addressGroup, "oobAuthority");
      oobChannel.getSubchannel().requestConnection();
    } else {
      Subchannel subchannel = helper.createSubchannel(addressGroup, Attributes.EMPTY);
      subchannel.requestConnection();
    }
    
    MockClientTransportInfo transportInfo = transports.poll();
    assertNotNull(transportInfo);

    // Transport closed when connecting
    assertEquals(0, resolver.refreshCalled);
    transportInfo.listener.transportShutdown(Status.UNAVAILABLE);
    assertEquals(1, resolver.refreshCalled);

    timer.forwardNanos(RECONNECT_BACKOFF_INTERVAL_NANOS);
    transportInfo = transports.poll();
    assertNotNull(transportInfo);

    transportInfo.listener.transportReady();

    // Transport closed when ready
    assertEquals(1, resolver.refreshCalled);
    transportInfo.listener.transportShutdown(Status.UNAVAILABLE);
    assertEquals(2, resolver.refreshCalled);
  }

  @Test
  public void uriPattern() {
    assertTrue(ManagedChannelImpl.URI_PATTERN.matcher("a:/").matches());
    assertTrue(ManagedChannelImpl.URI_PATTERN.matcher("Z019+-.:/!@ #~ ").matches());
    assertFalse(ManagedChannelImpl.URI_PATTERN.matcher("a/:").matches()); // "/:" not matched
    assertFalse(ManagedChannelImpl.URI_PATTERN.matcher("0a:/").matches()); // '0' not matched
    assertFalse(ManagedChannelImpl.URI_PATTERN.matcher("a,:/").matches()); // ',' not matched
    assertFalse(ManagedChannelImpl.URI_PATTERN.matcher(" a:/").matches()); // space not matched
  }

  /**
   * Test that information such as the Call's context, MethodDescriptor, authority, executor are
   * propagated to newStream() and applyRequestMetadata().
   */
  @Test
  public void informationPropagatedToNewStreamAndCallCredentials() {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);
    CallOptions callOptions = CallOptions.DEFAULT.withCallCredentials(creds);
    final Context.Key<String> testKey = Context.key("testing");
    Context ctx = Context.current().withValue(testKey, "testValue");
    final LinkedList<Context> credsApplyContexts = new LinkedList<Context>();
    final LinkedList<Context> newStreamContexts = new LinkedList<Context>();
    doAnswer(new Answer<Void>() {
        @Override
        public Void answer(InvocationOnMock in) throws Throwable {
          credsApplyContexts.add(Context.current());
          return null;
        }
      }).when(creds).applyRequestMetadata(
          any(MethodDescriptor.class), any(Attributes.class), any(Executor.class),
          any(MetadataApplier.class));

    // First call will be on delayed transport.  Only newCall() is run within the expected context,
    // so that we can verify that the context is explicitly attached before calling newStream() and
    // applyRequestMetadata(), which happens after we detach the context from the thread.
    Context origCtx = ctx.attach();
    assertEquals("testValue", testKey.get());
    ClientCall<String, Integer> call = channel.newCall(method, callOptions);
    ctx.detach(origCtx);
    assertNull(testKey.get());
    call.start(mockCallListener, new Metadata());

    // Simulate name resolution results
    EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(socketAddress);
    Subchannel subchannel = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    subchannel.requestConnection();
    verify(mockTransportFactory).newClientTransport(
        same(socketAddress), eq(authority), eq(userAgent), eq(noProxy));
    MockClientTransportInfo transportInfo = transports.poll();
    final ConnectionClientTransport transport = transportInfo.transport;
    when(transport.getAttributes()).thenReturn(Attributes.EMPTY);
    doAnswer(new Answer<ClientStream>() {
        @Override
        public ClientStream answer(InvocationOnMock in) throws Throwable {
          newStreamContexts.add(Context.current());
          return mock(ClientStream.class);
        }
      }).when(transport).newStream(
          any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class));

    verify(creds, never()).applyRequestMetadata(
        any(MethodDescriptor.class), any(Attributes.class), any(Executor.class),
        any(MetadataApplier.class));

    // applyRequestMetadata() is called after the transport becomes ready.
    transportInfo.listener.transportReady();
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    helper.updateBalancingState(READY, mockPicker);
    executor.runDueTasks();
    ArgumentCaptor<Attributes> attrsCaptor = ArgumentCaptor.forClass(Attributes.class);
    ArgumentCaptor<MetadataApplier> applierCaptor = ArgumentCaptor.forClass(MetadataApplier.class);
    verify(creds).applyRequestMetadata(same(method), attrsCaptor.capture(),
        same(executor.getScheduledExecutorService()), applierCaptor.capture());
    assertEquals("testValue", testKey.get(credsApplyContexts.poll()));
    assertEquals(authority, attrsCaptor.getValue().get(CallCredentials.ATTR_AUTHORITY));
    assertEquals(SecurityLevel.NONE,
        attrsCaptor.getValue().get(CallCredentials.ATTR_SECURITY_LEVEL));
    verify(transport, never()).newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class));

    // newStream() is called after apply() is called
    applierCaptor.getValue().apply(new Metadata());
    verify(transport).newStream(same(method), any(Metadata.class), same(callOptions));
    assertEquals("testValue", testKey.get(newStreamContexts.poll()));
    // The context should not live beyond the scope of newStream() and applyRequestMetadata()
    assertNull(testKey.get());


    // Second call will not be on delayed transport
    origCtx = ctx.attach();
    call = channel.newCall(method, callOptions);
    ctx.detach(origCtx);
    call.start(mockCallListener, new Metadata());

    verify(creds, times(2)).applyRequestMetadata(same(method), attrsCaptor.capture(),
        same(executor.getScheduledExecutorService()), applierCaptor.capture());
    assertEquals("testValue", testKey.get(credsApplyContexts.poll()));
    assertEquals(authority, attrsCaptor.getValue().get(CallCredentials.ATTR_AUTHORITY));
    assertEquals(SecurityLevel.NONE,
        attrsCaptor.getValue().get(CallCredentials.ATTR_SECURITY_LEVEL));
    // This is from the first call
    verify(transport).newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class));

    // Still, newStream() is called after apply() is called
    applierCaptor.getValue().apply(new Metadata());
    verify(transport, times(2)).newStream(same(method), any(Metadata.class), same(callOptions));
    assertEquals("testValue", testKey.get(newStreamContexts.poll()));

    assertNull(testKey.get());
  }

  @Test
  public void pickerReturnsStreamTracer_noDelay() {
    ClientStream mockStream = mock(ClientStream.class);
    ClientStreamTracer.Factory factory1 = mock(ClientStreamTracer.Factory.class);
    ClientStreamTracer.Factory factory2 = mock(ClientStreamTracer.Factory.class);
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);
    Subchannel subchannel = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    subchannel.requestConnection();
    MockClientTransportInfo transportInfo = transports.poll();
    transportInfo.listener.transportReady();
    ClientTransport mockTransport = transportInfo.transport;
    when(mockTransport.newStream(
            any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class)))
        .thenReturn(mockStream);

    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class))).thenReturn(
        PickResult.withSubchannel(subchannel, factory2));
    helper.updateBalancingState(READY, mockPicker);

    CallOptions callOptions = CallOptions.DEFAULT.withStreamTracerFactory(factory1);
    ClientCall<String, Integer> call = channel.newCall(method, callOptions);
    call.start(mockCallListener, new Metadata());

    verify(mockPicker).pickSubchannel(any(PickSubchannelArgs.class));
    verify(mockTransport).newStream(same(method), any(Metadata.class), callOptionsCaptor.capture());
    assertEquals(
        Arrays.asList(factory1, factory2),
        callOptionsCaptor.getValue().getStreamTracerFactories());
    // The factories are safely not stubbed because we do not expect any usage of them.
    verifyZeroInteractions(factory1);
    verifyZeroInteractions(factory2);
  }

  @Test
  public void pickerReturnsStreamTracer_delayed() {
    ClientStream mockStream = mock(ClientStream.class);
    ClientStreamTracer.Factory factory1 = mock(ClientStreamTracer.Factory.class);
    ClientStreamTracer.Factory factory2 = mock(ClientStreamTracer.Factory.class);
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);

    CallOptions callOptions = CallOptions.DEFAULT.withStreamTracerFactory(factory1);
    ClientCall<String, Integer> call = channel.newCall(method, callOptions);
    call.start(mockCallListener, new Metadata());

    Subchannel subchannel = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    subchannel.requestConnection();
    MockClientTransportInfo transportInfo = transports.poll();
    transportInfo.listener.transportReady();
    ClientTransport mockTransport = transportInfo.transport;
    when(mockTransport.newStream(
            any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class)))
        .thenReturn(mockStream);
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class))).thenReturn(
        PickResult.withSubchannel(subchannel, factory2));

    helper.updateBalancingState(READY, mockPicker);
    assertEquals(1, executor.runDueTasks());

    verify(mockPicker).pickSubchannel(any(PickSubchannelArgs.class));
    verify(mockTransport).newStream(same(method), any(Metadata.class), callOptionsCaptor.capture());
    assertEquals(
        Arrays.asList(factory1, factory2),
        callOptionsCaptor.getValue().getStreamTracerFactories());
    // The factories are safely not stubbed because we do not expect any usage of them.
    verifyZeroInteractions(factory1);
    verifyZeroInteractions(factory2);
  }

  @Test
  public void getState_loadBalancerSupportsChannelState() {
    createChannel(
        new FakeNameResolverFactory.Builder(expectedUri).setResolvedAtStart(false).build(),
        NO_INTERCEPTOR);
    assertEquals(IDLE, channel.getState(false));

    helper.updateBalancingState(TRANSIENT_FAILURE, mockPicker);
    assertEquals(TRANSIENT_FAILURE, channel.getState(false));
  }

  @Test
  public void getState_withRequestConnect() {
    createChannel(
        new FakeNameResolverFactory.Builder(expectedUri).setResolvedAtStart(false).build(),
        NO_INTERCEPTOR,
        false /* requestConnection */,
        ManagedChannelImpl.IDLE_TIMEOUT_MILLIS_DISABLE);

    assertEquals(IDLE, channel.getState(false));
    verify(mockLoadBalancerFactory, never()).newLoadBalancer(any(Helper.class));

    // call getState() with requestConnection = true
    assertEquals(IDLE, channel.getState(true));
    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(null);
    verify(mockLoadBalancerFactory).newLoadBalancer(helperCaptor.capture());
    helper = helperCaptor.getValue();

    helper.updateBalancingState(CONNECTING, mockPicker);
    assertEquals(CONNECTING, channel.getState(false));
    assertEquals(CONNECTING, channel.getState(true));
    verifyNoMoreInteractions(mockLoadBalancerFactory);
  }

  @Test
  public void getState_withRequestConnect_IdleWithLbRunning() {
    createChannel(
        new FakeNameResolverFactory.Builder(expectedUri).setResolvedAtStart(false).build(),
        NO_INTERCEPTOR,
        true /* requestConnection */,
        ManagedChannelImpl.IDLE_TIMEOUT_MILLIS_DISABLE);
    verify(mockLoadBalancerFactory).newLoadBalancer(any(Helper.class));

    helper.updateBalancingState(IDLE, mockPicker);

    assertEquals(IDLE, channel.getState(true));
    verifyNoMoreInteractions(mockLoadBalancerFactory);
    verify(mockPicker).requestConnection();
  }

  @Test
  public void notifyWhenStateChanged() {
    final AtomicBoolean stateChanged = new AtomicBoolean();
    Runnable onStateChanged = new Runnable() {
      @Override
      public void run() {
        stateChanged.set(true);
      }
    };

    createChannel(
        new FakeNameResolverFactory.Builder(expectedUri).setResolvedAtStart(false).build(),
        NO_INTERCEPTOR);
    assertEquals(IDLE, channel.getState(false));

    channel.notifyWhenStateChanged(IDLE, onStateChanged);
    executor.runDueTasks();
    assertFalse(stateChanged.get());

    // state change from IDLE to CONNECTING
    helper.updateBalancingState(CONNECTING, mockPicker);
    // onStateChanged callback should run
    executor.runDueTasks();
    assertTrue(stateChanged.get());

    // clear and test form CONNECTING
    stateChanged.set(false);
    channel.notifyWhenStateChanged(IDLE, onStateChanged);
    // onStateChanged callback should run immediately
    executor.runDueTasks();
    assertTrue(stateChanged.get());
  }

  @Test
  public void channelStateWhenChannelShutdown() {
    final AtomicBoolean stateChanged = new AtomicBoolean();
    Runnable onStateChanged = new Runnable() {
      @Override
      public void run() {
        stateChanged.set(true);
      }
    };

    createChannel(
        new FakeNameResolverFactory.Builder(expectedUri).setResolvedAtStart(false).build(),
        NO_INTERCEPTOR);
    assertEquals(IDLE, channel.getState(false));
    channel.notifyWhenStateChanged(IDLE, onStateChanged);
    executor.runDueTasks();
    assertFalse(stateChanged.get());

    channel.shutdown();
    assertEquals(SHUTDOWN, channel.getState(false));
    executor.runDueTasks();
    assertTrue(stateChanged.get());

    stateChanged.set(false);
    channel.notifyWhenStateChanged(SHUTDOWN, onStateChanged);
    helper.updateBalancingState(CONNECTING, mockPicker);

    assertEquals(SHUTDOWN, channel.getState(false));
    executor.runDueTasks();
    assertFalse(stateChanged.get());
  }

  @Test
  public void stateIsIdleOnIdleTimeout() {
    long idleTimeoutMillis = 2000L;
    createChannel(
        new FakeNameResolverFactory.Builder(expectedUri).build(),
        NO_INTERCEPTOR,
        true /* request connection*/,
        idleTimeoutMillis);
    assertEquals(IDLE, channel.getState(false));

    helper.updateBalancingState(CONNECTING, mockPicker);
    assertEquals(CONNECTING, channel.getState(false));

    timer.forwardNanos(TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis));
    assertEquals(IDLE, channel.getState(false));
  }

  @Test
  public void panic_whenIdle() {
    subtestPanic(IDLE);
  }

  @Test
  public void panic_whenConnecting() {
    subtestPanic(CONNECTING);
  }

  @Test
  public void panic_whenTransientFailure() {
    subtestPanic(TRANSIENT_FAILURE);
  }

  @Test
  public void panic_whenReady() {
    subtestPanic(READY);
  }

  private void subtestPanic(ConnectivityState initialState) {
    assertNotEquals("We don't test panic mode if it's already SHUTDOWN", SHUTDOWN, initialState);
    long idleTimeoutMillis = 2000L;
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).build();
    createChannel(nameResolverFactory, NO_INTERCEPTOR, true, idleTimeoutMillis);

    verify(mockLoadBalancerFactory).newLoadBalancer(any(Helper.class));
    assertEquals(1, nameResolverFactory.resolvers.size());
    FakeNameResolverFactory.FakeNameResolver resolver = nameResolverFactory.resolvers.remove(0);

    Throwable panicReason = new Exception("Simulated uncaught exception");
    if (initialState == IDLE) {
      timer.forwardNanos(TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis));
    } else {
      helper.updateBalancingState(initialState, mockPicker);
    }
    assertEquals(initialState, channel.getState(false));

    if (initialState == IDLE) {
      // IDLE mode will shutdown resolver and balancer
      verify(mockLoadBalancer).shutdown();
      assertTrue(resolver.shutdown);
      // A new resolver is created
      assertEquals(1, nameResolverFactory.resolvers.size());
      resolver = nameResolverFactory.resolvers.remove(0);
      assertFalse(resolver.shutdown);
    } else {
      verify(mockLoadBalancer, never()).shutdown();
      assertFalse(resolver.shutdown);
    }

    // Make channel panic!
    channel.panic(panicReason);

    // Calls buffered in delayedTransport will fail

    // Resolver and balancer are shutdown
    verify(mockLoadBalancer).shutdown();
    assertTrue(resolver.shutdown);

    // Channel will stay in TRANSIENT_FAILURE. getState(true) will not revive it.
    assertEquals(TRANSIENT_FAILURE, channel.getState(true));
    assertEquals(TRANSIENT_FAILURE, channel.getState(true));
    verifyPanicMode(panicReason);

    // No new resolver or balancer are created
    verifyNoMoreInteractions(mockLoadBalancerFactory);
    assertEquals(0, nameResolverFactory.resolvers.size());

    // A misbehaving balancer that calls updateBalancingState() after it's shut down will not be
    // able to revive it.
    helper.updateBalancingState(READY, mockPicker);
    verifyPanicMode(panicReason);

    // Cannot be revived by exitIdleMode()
    channel.exitIdleMode();
    verifyPanicMode(panicReason);

    // Can still shutdown normally
    channel.shutdown();
    assertTrue(channel.isShutdown());
    assertTrue(channel.isTerminated());
    assertEquals(SHUTDOWN, channel.getState(false));

    // We didn't stub mockPicker, because it should have never been called in this test.
    verifyZeroInteractions(mockPicker);
  }

  @Test
  public void panic_bufferedCallsWillFail() {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).build();
    createChannel(nameResolverFactory, NO_INTERCEPTOR);

    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withNoResult());
    helper.updateBalancingState(CONNECTING, mockPicker);

    // Start RPCs that will be buffered in delayedTransport
    ClientCall<String, Integer> call =
        channel.newCall(method, CallOptions.DEFAULT.withoutWaitForReady());
    call.start(mockCallListener, new Metadata());

    ClientCall<String, Integer> call2 =
        channel.newCall(method, CallOptions.DEFAULT.withWaitForReady());
    call2.start(mockCallListener2, new Metadata());

    executor.runDueTasks();
    verifyZeroInteractions(mockCallListener, mockCallListener2);

    // Enter panic
    Throwable panicReason = new Exception("Simulated uncaught exception");
    channel.panic(panicReason);

    // Buffered RPCs fail immediately
    executor.runDueTasks();
    verifyCallListenerClosed(mockCallListener, Status.Code.INTERNAL, panicReason);
    verifyCallListenerClosed(mockCallListener2, Status.Code.INTERNAL, panicReason);
  }

  private void verifyPanicMode(Throwable cause) {
    Assume.assumeTrue("Panic mode disabled to resolve issues with some tests. See #3293", false);

    @SuppressWarnings("unchecked")
    ClientCall.Listener<Integer> mockListener =
        (ClientCall.Listener<Integer>) mock(ClientCall.Listener.class);
    assertEquals(TRANSIENT_FAILURE, channel.getState(false));
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(mockListener, new Metadata());
    executor.runDueTasks();
    verifyCallListenerClosed(mockListener, Status.Code.INTERNAL, cause);

    // Channel is dead.  No more pending task to possibly revive it.
    assertEquals(0, timer.numPendingTasks());
    assertEquals(0, executor.numPendingTasks());
    assertEquals(0, oobExecutor.numPendingTasks());
  }

  private void verifyCallListenerClosed(
      ClientCall.Listener<Integer> listener, Status.Code code, Throwable cause) {
    ArgumentCaptor<Status> captor = ArgumentCaptor.forClass(null);
    verify(listener).onClose(captor.capture(), any(Metadata.class));
    Status rpcStatus = captor.getValue();
    assertEquals(code, rpcStatus.getCode());
    assertSame(cause, rpcStatus.getCause());
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void idleTimeoutAndReconnect() {
    long idleTimeoutMillis = 2000L;
    createChannel(
        new FakeNameResolverFactory.Builder(expectedUri).build(),
        NO_INTERCEPTOR,
        true /* request connection*/,
        idleTimeoutMillis);

    timer.forwardNanos(TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis));
    assertEquals(IDLE, channel.getState(true /* request connection */));

    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(Helper.class);
    // Two times of requesting connection will create loadBalancer twice.
    verify(mockLoadBalancerFactory, times(2)).newLoadBalancer(helperCaptor.capture());
    Helper helper2 = helperCaptor.getValue();

    // Updating on the old helper (whose balancer has been shutdown) does not change the channel
    // state.
    helper.updateBalancingState(CONNECTING, mockPicker);
    assertEquals(IDLE, channel.getState(false));

    helper2.updateBalancingState(CONNECTING, mockPicker);
    assertEquals(CONNECTING, channel.getState(false));
  }

  @Test
  public void idleMode_resetsDelayedTransportPicker() {
    ClientStream mockStream = mock(ClientStream.class);
    Status pickError = Status.UNAVAILABLE.withDescription("pick result error");
    long idleTimeoutMillis = 1000L;
    createChannel(
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build(),
        NO_INTERCEPTOR,
        true,
        idleTimeoutMillis);
    assertEquals(IDLE, channel.getState(false));

    // This call will be buffered in delayedTransport
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, new Metadata());

    // Move channel into TRANSIENT_FAILURE, which will fail the pending call
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withError(pickError));
    helper.updateBalancingState(TRANSIENT_FAILURE, mockPicker);
    assertEquals(TRANSIENT_FAILURE, channel.getState(false));
    executor.runDueTasks();
    verify(mockCallListener).onClose(same(pickError), any(Metadata.class));

    // Move channel to idle
    timer.forwardNanos(TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis));
    assertEquals(IDLE, channel.getState(false));

    // This call should be buffered, but will move the channel out of idle
    ClientCall<String, Integer> call2 = channel.newCall(method, CallOptions.DEFAULT);
    call2.start(mockCallListener2, new Metadata());
    executor.runDueTasks();
    verifyNoMoreInteractions(mockCallListener2);

    // Get the helper created on exiting idle
    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(Helper.class);
    verify(mockLoadBalancerFactory, times(2)).newLoadBalancer(helperCaptor.capture());
    Helper helper2 = helperCaptor.getValue();

    // Establish a connection
    Subchannel subchannel = helper2.createSubchannel(addressGroup, Attributes.EMPTY);
    subchannel.requestConnection();
    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    when(mockTransport.newStream(same(method), any(Metadata.class), any(CallOptions.class)))
        .thenReturn(mockStream);
    transportListener.transportReady();

    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    helper2.updateBalancingState(READY, mockPicker);
    assertEquals(READY, channel.getState(false));
    executor.runDueTasks();

    // Verify the buffered call was drained
    verify(mockTransport).newStream(same(method), any(Metadata.class), any(CallOptions.class));
    verify(mockStream).start(any(ClientStreamListener.class));
  }

  @Test
  public void enterIdleEntersIdle() {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);
    helper.updateBalancingState(READY, mockPicker);
    assertEquals(READY, channel.getState(false));

    channel.enterIdle();

    assertEquals(IDLE, channel.getState(false));
  }

  @Test
  public void enterIdleAfterIdleTimerIsNoOp() {
    long idleTimeoutMillis = 2000L;
    createChannel(
        new FakeNameResolverFactory.Builder(expectedUri).build(),
        NO_INTERCEPTOR,
        true /* request connection*/,
        idleTimeoutMillis);
    timer.forwardNanos(TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis));
    assertEquals(IDLE, channel.getState(false));

    channel.enterIdle();

    assertEquals(IDLE, channel.getState(false));
  }

  @Test
  public void updateBalancingStateDoesUpdatePicker() {
    ClientStream mockStream = mock(ClientStream.class);
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);

    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, new Metadata());

    // Make the transport available with subchannel2
    Subchannel subchannel1 = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    Subchannel subchannel2 = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    subchannel2.requestConnection();

    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    when(mockTransport.newStream(same(method), any(Metadata.class), any(CallOptions.class)))
        .thenReturn(mockStream);
    transportListener.transportReady();

    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel1));
    helper.updateBalancingState(READY, mockPicker);

    executor.runDueTasks();
    verify(mockTransport, never())
        .newStream(any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class));
    verify(mockStream, never()).start(any(ClientStreamListener.class));


    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel2));
    helper.updateBalancingState(READY, mockPicker);

    executor.runDueTasks();
    verify(mockTransport).newStream(same(method), any(Metadata.class), any(CallOptions.class));
    verify(mockStream).start(any(ClientStreamListener.class));
  }

  @Test
  public void updateBalancingStateWithShutdownShouldBeIgnored() {
    createChannel(
        new FakeNameResolverFactory.Builder(expectedUri).setResolvedAtStart(false).build(),
        NO_INTERCEPTOR);
    assertEquals(IDLE, channel.getState(false));

    Runnable onStateChanged = mock(Runnable.class);
    channel.notifyWhenStateChanged(IDLE, onStateChanged);

    helper.updateBalancingState(SHUTDOWN, mockPicker);

    assertEquals(IDLE, channel.getState(false));
    executor.runDueTasks();
    verify(onStateChanged, never()).run();
  }

  @Test
  public void resetConnectBackoff() {
    // Start with a name resolution failure to trigger backoff attempts
    Status error = Status.UNAVAILABLE.withCause(new Throwable("fake name resolution error"));
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).setError(error).build();
    // Name resolution is started as soon as channel is created.
    createChannel(nameResolverFactory, NO_INTERCEPTOR);
    FakeNameResolverFactory.FakeNameResolver resolver = nameResolverFactory.resolvers.get(0);
    verify(mockLoadBalancer).handleNameResolutionError(same(error));

    FakeClock.ScheduledTask nameResolverBackoff = getNameResolverRefresh();
    assertNotNull("There should be a name resolver backoff task", nameResolverBackoff);
    assertEquals(0, resolver.refreshCalled);

    // Verify resetConnectBackoff() calls refresh and cancels the scheduled backoff
    channel.resetConnectBackoff();
    assertEquals(1, resolver.refreshCalled);
    assertTrue(nameResolverBackoff.isCancelled());

    // Simulate a race between cancel and the task scheduler. Should be a no-op.
    nameResolverBackoff.command.run();
    assertEquals(1, resolver.refreshCalled);

    // Verify that the reconnect policy was recreated and the backoff multiplier reset to 1
    timer.forwardNanos(RECONNECT_BACKOFF_INTERVAL_NANOS);
    assertEquals(2, resolver.refreshCalled);
  }

  @Test
  public void resetConnectBackoff_noOpWithoutPendingResolverBackoff() {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build();
    createChannel(nameResolverFactory, NO_INTERCEPTOR);
    FakeNameResolverFactory.FakeNameResolver nameResolver = nameResolverFactory.resolvers.get(0);
    assertEquals(0, nameResolver.refreshCalled);

    channel.resetConnectBackoff();

    assertEquals(0, nameResolver.refreshCalled);
  }

  @Test
  public void resetConnectBackoff_noOpWhenChannelShutdown() {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).build();
    createChannel(nameResolverFactory, NO_INTERCEPTOR);

    channel.shutdown();
    assertTrue(channel.isShutdown());
    channel.resetConnectBackoff();

    FakeNameResolverFactory.FakeNameResolver nameResolver = nameResolverFactory.resolvers.get(0);
    assertEquals(0, nameResolver.refreshCalled);
  }

  @Test
  public void resetConnectBackoff_noOpWhenNameResolverNotStarted() {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).build();
    createChannel(nameResolverFactory, NO_INTERCEPTOR, false /* requestConnection */,
            ManagedChannelImpl.IDLE_TIMEOUT_MILLIS_DISABLE);

    channel.resetConnectBackoff();

    FakeNameResolverFactory.FakeNameResolver nameResolver = nameResolverFactory.resolvers.get(0);
    assertEquals(0, nameResolver.refreshCalled);
  }

  @Test
  public void channelsAndSubchannels_instrumented_name() throws Exception {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);
    assertEquals(target, getStats(channel).target);

    Subchannel subchannel = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    assertEquals(addressGroup.toString(), getStats((AbstractSubchannel) subchannel).target);
  }

  @Test
  public void channelsAndSubchannels_instrumented_state() throws Exception {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);

    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(null);
    verify(mockLoadBalancerFactory).newLoadBalancer(helperCaptor.capture());
    helper = helperCaptor.getValue();

    assertEquals(IDLE, getStats(channel).state);
    helper.updateBalancingState(CONNECTING, mockPicker);
    assertEquals(CONNECTING, getStats(channel).state);

    AbstractSubchannel subchannel =
        (AbstractSubchannel) helper.createSubchannel(addressGroup, Attributes.EMPTY);

    assertEquals(IDLE, getStats(subchannel).state);
    subchannel.requestConnection();
    assertEquals(CONNECTING, getStats(subchannel).state);

    MockClientTransportInfo transportInfo = transports.poll();

    assertEquals(CONNECTING, getStats(subchannel).state);
    transportInfo.listener.transportReady();
    assertEquals(READY, getStats(subchannel).state);

    assertEquals(CONNECTING, getStats(channel).state);
    helper.updateBalancingState(READY, mockPicker);
    assertEquals(READY, getStats(channel).state);

    channel.shutdownNow();
    assertEquals(SHUTDOWN, getStats(channel).state);
    assertEquals(SHUTDOWN, getStats(subchannel).state);
  }

  @Test
  public void channelStat_callStarted() throws Exception {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    assertEquals(0, getStats(channel).callsStarted);
    call.start(mockCallListener, new Metadata());
    assertEquals(1, getStats(channel).callsStarted);
    assertEquals(executor.currentTimeMillis(), getStats(channel).lastCallStartedMillis);
  }

  @Test
  public void channelsAndSubChannels_instrumented_success() throws Exception {
    channelsAndSubchannels_instrumented0(true);
  }

  @Test
  public void channelsAndSubChannels_instrumented_fail() throws Exception {
    channelsAndSubchannels_instrumented0(false);
  }

  private void channelsAndSubchannels_instrumented0(boolean success) throws Exception {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);

    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);

    // Channel stat bumped when ClientCall.start() called
    assertEquals(0, getStats(channel).callsStarted);
    call.start(mockCallListener, new Metadata());
    assertEquals(1, getStats(channel).callsStarted);

    ClientStream mockStream = mock(ClientStream.class);
    ClientStreamTracer.Factory factory = mock(ClientStreamTracer.Factory.class);
    AbstractSubchannel subchannel =
        (AbstractSubchannel) helper.createSubchannel(addressGroup, Attributes.EMPTY);
    subchannel.requestConnection();
    MockClientTransportInfo transportInfo = transports.poll();
    transportInfo.listener.transportReady();
    ClientTransport mockTransport = transportInfo.transport;
    when(mockTransport.newStream(
            any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class)))
        .thenReturn(mockStream);
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class))).thenReturn(
        PickResult.withSubchannel(subchannel, factory));

    // subchannel stat bumped when call gets assigned to it
    assertEquals(0, getStats(subchannel).callsStarted);
    helper.updateBalancingState(READY, mockPicker);
    assertEquals(1, executor.runDueTasks());
    verify(mockStream).start(streamListenerCaptor.capture());
    assertEquals(1, getStats(subchannel).callsStarted);

    ClientStreamListener streamListener = streamListenerCaptor.getValue();
    call.halfClose();

    // closing stream listener affects subchannel stats immediately
    assertEquals(0, getStats(subchannel).callsSucceeded);
    assertEquals(0, getStats(subchannel).callsFailed);
    streamListener.closed(success ? Status.OK : Status.UNKNOWN, new Metadata());
    if (success) {
      assertEquals(1, getStats(subchannel).callsSucceeded);
      assertEquals(0, getStats(subchannel).callsFailed);
    } else {
      assertEquals(0, getStats(subchannel).callsSucceeded);
      assertEquals(1, getStats(subchannel).callsFailed);
    }

    // channel stats bumped when the ClientCall.Listener is notified
    assertEquals(0, getStats(channel).callsSucceeded);
    assertEquals(0, getStats(channel).callsFailed);
    executor.runDueTasks();
    if (success) {
      assertEquals(1, getStats(channel).callsSucceeded);
      assertEquals(0, getStats(channel).callsFailed);
    } else {
      assertEquals(0, getStats(channel).callsSucceeded);
      assertEquals(1, getStats(channel).callsFailed);
    }
  }

  @Test
  public void channelsAndSubchannels_oob_instrumented_success() throws Exception {
    channelsAndSubchannels_oob_instrumented0(true);
  }

  @Test
  public void channelsAndSubchannels_oob_instrumented_fail() throws Exception {
    channelsAndSubchannels_oob_instrumented0(false);
  }

  private void channelsAndSubchannels_oob_instrumented0(boolean success) throws Exception {
    // set up
    ClientStream mockStream = mock(ClientStream.class);
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);

    OobChannel oobChannel = (OobChannel) helper.createOobChannel(addressGroup, "oobauthority");
    AbstractSubchannel oobSubchannel = (AbstractSubchannel) oobChannel.getSubchannel();
    FakeClock callExecutor = new FakeClock();
    CallOptions options =
        CallOptions.DEFAULT.withExecutor(callExecutor.getScheduledExecutorService());
    ClientCall<String, Integer> call = oobChannel.newCall(method, options);
    Metadata headers = new Metadata();

    // Channel stat bumped when ClientCall.start() called
    assertEquals(0, getStats(oobChannel).callsStarted);
    call.start(mockCallListener, headers);
    assertEquals(1, getStats(oobChannel).callsStarted);

    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    when(mockTransport.newStream(same(method), same(headers), any(CallOptions.class)))
        .thenReturn(mockStream);

    // subchannel stat bumped when call gets assigned to it
    assertEquals(0, getStats(oobSubchannel).callsStarted);
    transportListener.transportReady();
    callExecutor.runDueTasks();
    verify(mockStream).start(streamListenerCaptor.capture());
    assertEquals(1, getStats(oobSubchannel).callsStarted);

    ClientStreamListener streamListener = streamListenerCaptor.getValue();
    call.halfClose();

    // closing stream listener affects subchannel stats immediately
    assertEquals(0, getStats(oobSubchannel).callsSucceeded);
    assertEquals(0, getStats(oobSubchannel).callsFailed);
    streamListener.closed(success ? Status.OK : Status.UNKNOWN, new Metadata());
    if (success) {
      assertEquals(1, getStats(oobSubchannel).callsSucceeded);
      assertEquals(0, getStats(oobSubchannel).callsFailed);
    } else {
      assertEquals(0, getStats(oobSubchannel).callsSucceeded);
      assertEquals(1, getStats(oobSubchannel).callsFailed);
    }

    // channel stats bumped when the ClientCall.Listener is notified
    assertEquals(0, getStats(oobChannel).callsSucceeded);
    assertEquals(0, getStats(oobChannel).callsFailed);
    callExecutor.runDueTasks();
    if (success) {
      assertEquals(1, getStats(oobChannel).callsSucceeded);
      assertEquals(0, getStats(oobChannel).callsFailed);
    } else {
      assertEquals(0, getStats(oobChannel).callsSucceeded);
      assertEquals(1, getStats(oobChannel).callsFailed);
    }
    // oob channel is separate from the original channel
    assertEquals(0, getStats(channel).callsSucceeded);
    assertEquals(0, getStats(channel).callsFailed);
  }

  @Test
  public void channelsAndSubchannels_oob_instrumented_name() throws Exception {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);

    String authority = "oobauthority";
    OobChannel oobChannel = (OobChannel) helper.createOobChannel(addressGroup, authority);
    assertEquals(authority, getStats(oobChannel).target);
  }

  @Test
  public void channelsAndSubchannels_oob_instrumented_state() throws Exception {
    createChannel(new FakeNameResolverFactory.Builder(expectedUri).build(), NO_INTERCEPTOR);

    OobChannel oobChannel = (OobChannel) helper.createOobChannel(addressGroup, "oobauthority");
    assertEquals(IDLE, getStats(oobChannel).state);

    oobChannel.getSubchannel().requestConnection();
    assertEquals(CONNECTING, getStats(oobChannel).state);

    MockClientTransportInfo transportInfo = transports.poll();
    ManagedClientTransport.Listener transportListener = transportInfo.listener;

    transportListener.transportReady();
    assertEquals(READY, getStats(oobChannel).state);

    // oobchannel state is separate from the ManagedChannel
    assertEquals(IDLE, getStats(channel).state);
    channel.shutdownNow();
    assertEquals(SHUTDOWN, getStats(channel).state);
    assertEquals(SHUTDOWN, getStats(oobChannel).state);
  }

  @Test
  public void binaryLogInterceptor_eventOrdering() throws Exception {
    final List<ClientInterceptor> recvInitialMetadata = new ArrayList<ClientInterceptor>();
    final List<ClientInterceptor> sendInitialMetadata = new ArrayList<ClientInterceptor>();
    final List<ClientInterceptor> sendReq = new ArrayList<ClientInterceptor>();
    final List<ClientInterceptor> recvResp = new ArrayList<ClientInterceptor>();
    final List<ClientInterceptor> recvTrailingMetadata = new ArrayList<ClientInterceptor>();
    final class TracingClientInterceptor implements ClientInterceptor {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method,
          CallOptions callOptions,
          Channel next) {
        final ClientInterceptor ref = this;
        return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
          @Override
          public void start(Listener<RespT> responseListener, Metadata headers) {
            sendInitialMetadata.add(ref);
            ClientCall.Listener<RespT> wListener =
                new SimpleForwardingClientCallListener<RespT>(responseListener) {
                  @Override
                  public void onMessage(RespT message) {
                    recvResp.add(ref);
                    super.onMessage(message);
                  }

                  @Override
                  public void onHeaders(Metadata headers) {
                    recvInitialMetadata.add(ref);
                    super.onHeaders(headers);
                  }

                  @Override
                  public void onClose(Status status, Metadata trailers) {
                    recvTrailingMetadata.add(ref);
                    super.onClose(status, trailers);
                  }
                };
            super.start(wListener, headers);
          }

          @Override
          public void sendMessage(ReqT message) {
            sendReq.add(ref);
            super.sendMessage(message);
          }
        };
      }
    }

    TracingClientInterceptor userInterceptor = new TracingClientInterceptor();
    final TracingClientInterceptor binlogInterceptor = new TracingClientInterceptor();
    binlogProvider = new BinaryLogProvider() {
      @Nullable
      @Override
      public ServerInterceptor getServerInterceptor(String fullMethodName) {
        return null;
      }

      @Override
      public ClientInterceptor getClientInterceptor(String fullMethodName) {
        return binlogInterceptor;
      }

      @Override
      protected int priority() {
        return 0;
      }

      @Override
      protected boolean isAvailable() {
        return true;
      }
    };

    // perform an RPC
    Metadata headers = new Metadata();
    ClientStream mockStream = mock(ClientStream.class);
    createChannel(
        new FakeNameResolverFactory.Builder(expectedUri).build(),
        ImmutableList.<ClientInterceptor>of(userInterceptor));
    CallOptions options =
        CallOptions.DEFAULT.withExecutor(executor.getScheduledExecutorService());
    ClientCall<String, Integer> call = channel.newCall(method, options);
    call.start(mockCallListener, headers);

    Subchannel subchannel = helper.createSubchannel(addressGroup, Attributes.EMPTY);
    subchannel.requestConnection();
    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    // binlog modifies the MethodDescriptor, so we can not use the same() matcher
    when(mockTransport.newStream(
        any(MethodDescriptor.class),
        same(headers),
        any(CallOptions.class)))
        .thenReturn(mockStream);
    transportListener.transportReady();
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    helper.updateBalancingState(READY, mockPicker);

    executor.runDueTasks();
    verify(mockStream).start(streamListenerCaptor.capture());

    ClientStreamListener streamListener = streamListenerCaptor.getValue();
    streamListener.headersRead(new Metadata());
    assertEquals(1, executor.runDueTasks());
    String actualRequest = "hello world";
    call.sendMessage(actualRequest);
    streamListener.messagesAvailable(new SingleMessageProducer(method.streamResponse(1234)));
    assertEquals(1, executor.runDueTasks());
    call.halfClose();
    streamListener.closed(Status.OK, new Metadata());
    assertEquals(1, executor.runDueTasks());
    // end: perform an RPC

    assertThat(recvInitialMetadata).containsExactly(binlogInterceptor, userInterceptor).inOrder();
    assertThat(sendInitialMetadata).containsExactly(userInterceptor, binlogInterceptor).inOrder();
    assertThat(sendReq).containsExactly(userInterceptor, binlogInterceptor).inOrder();
    assertThat(recvResp).containsExactly(binlogInterceptor, userInterceptor).inOrder();
    assertThat(recvTrailingMetadata).containsExactly(binlogInterceptor, userInterceptor);
  }

  @Test
  public void binaryLogInterceptor_intercept_reqResp() throws Exception {
    final class TracingClientInterceptor implements ClientInterceptor {
      private final List<MethodDescriptor<?, ?>> interceptedMethods =
          new ArrayList<MethodDescriptor<?, ?>>();

      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        interceptedMethods.add(method);
        return next.newCall(method, callOptions);
      }
    }

    TracingClientInterceptor userInterceptor = new TracingClientInterceptor();
    binlogProvider = new BinaryLogProvider() {
      @Nullable
      @Override
      public ServerInterceptor getServerInterceptor(String fullMethodName) {
        return null;
      }

      @Override
      public ClientInterceptor getClientInterceptor(String fullMethodName) {
        return new TracingClientInterceptor();
      }

      @Override
      protected int priority() {
        return 0;
      }

      @Override
      protected boolean isAvailable() {
        return true;
      }
    };
    createChannel(
        new FakeNameResolverFactory.Builder(expectedUri).build(),
        Collections.<ClientInterceptor>singletonList(userInterceptor));
    ClientCall<String, Integer> call =
        channel.newCall(method, CallOptions.DEFAULT.withDeadlineAfter(0, TimeUnit.NANOSECONDS));
    ClientCall.Listener<Integer> listener = new NoopClientCallListener<Integer>();
    call.start(listener, new Metadata());
    assertEquals(1, executor.runDueTasks());

    String actualRequest = "hello world";
    call.sendMessage(actualRequest);

    // The user supplied interceptor must still operate on the original message types
    assertThat(userInterceptor.interceptedMethods).hasSize(1);
    assertSame(
        method.getRequestMarshaller(),
        userInterceptor.interceptedMethods.get(0).getRequestMarshaller());
    assertSame(
        method.getResponseMarshaller(),
        userInterceptor.interceptedMethods.get(0).getResponseMarshaller());
  }

  private static class FakeBackoffPolicyProvider implements BackoffPolicy.Provider {
    @Override
    public BackoffPolicy get() {
      return new BackoffPolicy() {
        private int multiplier = 1;

        @Override
        public long nextBackoffNanos() {
          return RECONNECT_BACKOFF_INTERVAL_NANOS * multiplier++;
        }
      };
    }
  }

  private static class FakeNameResolverFactory extends NameResolver.Factory {
    private final URI expectedUri;
    private final List<EquivalentAddressGroup> servers;
    private final boolean resolvedAtStart;
    private final Status error;
    private final ArrayList<FakeNameResolver> resolvers = new ArrayList<FakeNameResolver>();

    private FakeNameResolverFactory(
        URI expectedUri,
        List<EquivalentAddressGroup> servers,
        boolean resolvedAtStart,
        Status error) {
      this.expectedUri = expectedUri;
      this.servers = servers;
      this.resolvedAtStart = resolvedAtStart;
      this.error = error;
    }

    @Override
    public NameResolver newNameResolver(final URI targetUri, Attributes params) {
      if (!expectedUri.equals(targetUri)) {
        return null;
      }
      assertSame(NAME_RESOLVER_PARAMS, params);
      FakeNameResolver resolver = new FakeNameResolver(error);
      resolvers.add(resolver);
      return resolver;
    }

    @Override
    public String getDefaultScheme() {
      return "fake";
    }

    void allResolved() {
      for (FakeNameResolver resolver : resolvers) {
        resolver.resolved();
      }
    }

    private class FakeNameResolver extends NameResolver {
      Listener listener;
      boolean shutdown;
      int refreshCalled;
      Status error;

      FakeNameResolver(Status error) {
        this.error = error;
      }

      @Override public String getServiceAuthority() {
        return expectedUri.getAuthority();
      }

      @Override public void start(final Listener listener) {
        this.listener = listener;
        if (resolvedAtStart) {
          resolved();
        }
      }

      @Override public void refresh() {
        assertNotNull(listener);
        refreshCalled++;
        resolved();
      }

      void resolved() {
        if (error != null) {
          listener.onError(error);
          return;
        }
        listener.onAddresses(servers, Attributes.EMPTY);
      }

      @Override public void shutdown() {
        shutdown = true;
      }
    }

    private static class Builder {
      private final URI expectedUri;
      List<EquivalentAddressGroup> servers = ImmutableList.<EquivalentAddressGroup>of();
      boolean resolvedAtStart = true;
      Status error = null;

      private Builder(URI expectedUri) {
        this.expectedUri = expectedUri;
      }

      private Builder setServers(List<EquivalentAddressGroup> servers) {
        this.servers = servers;
        return this;
      }

      private Builder setResolvedAtStart(boolean resolvedAtStart) {
        this.resolvedAtStart = resolvedAtStart;
        return this;
      }

      private Builder setError(Status error) {
        this.error = error;
        return this;
      }

      private FakeNameResolverFactory build() {
        return new FakeNameResolverFactory(expectedUri, servers, resolvedAtStart, error);
      }
    }
  }

  private static ChannelStats getStats(AbstractSubchannel subchannel) throws Exception {
    return subchannel.getInternalSubchannel().getStats().get();
  }

  private static ChannelStats getStats(
      Instrumented<ChannelStats> instrumented) throws Exception {
    return instrumented.getStats().get();
  }

  private FakeClock.ScheduledTask getNameResolverRefresh() {
    return Iterables.getOnlyElement(timer.getPendingTasks(NAME_RESOLVER_REFRESH_TASK_FILTER), null);
  }
}
