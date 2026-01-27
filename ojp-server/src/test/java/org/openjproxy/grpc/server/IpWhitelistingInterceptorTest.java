package org.openjproxy.grpc.server;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for IpWhitelistingInterceptor.
 */
class IpWhitelistingInterceptorTest {

    @Test
    void testAllowedIpAccess() {
        // Setup
        List<String> allowedIps = List.of("192.168.1.1", "10.0.0.0/8");
        IpWhitelistingInterceptor interceptor = new IpWhitelistingInterceptor(allowedIps);

        ServerCall<String, String> call = createMockServerCall("192.168.1.1");
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
        ServerCall.Listener<String> mockListener = mock(ServerCall.Listener.class);
        when(next.startCall(any(), any())).thenReturn(mockListener);

        // Execute
        ServerCall.Listener<String> result = interceptor.interceptCall(call, new Metadata(), next);

        // Verify
        assertNotNull(result);
        verify(next, times(1)).startCall(any(), any());
        verify(call, never()).close(any(), any());
    }

    @Test
    void testDeniedIpAccess() {
        // Setup
        List<String> allowedIps = List.of("192.168.1.1", "10.0.0.0/8");
        IpWhitelistingInterceptor interceptor = new IpWhitelistingInterceptor(allowedIps);

        ServerCall<String, String> call = createMockServerCall("203.0.113.1");
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);

        // Execute
        ServerCall.Listener<String> result = interceptor.interceptCall(call, new Metadata(), next);

        // Verify
        assertNotNull(result);
        verify(next, never()).startCall(any(), any());

        // Capture the Status argument passed to close
        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(Metadata.class);
        verify(call, times(1)).close(statusCaptor.capture(), metadataCaptor.capture());

        // Verify the status is PERMISSION_DENIED
        assertEquals(Status.Code.PERMISSION_DENIED, statusCaptor.getValue().getCode());
        assertEquals("Access denied", statusCaptor.getValue().getDescription());
    }

    @Test
    void testAllowedIpAccessWithCidr() {
        // Setup
        List<String> allowedIps = List.of("10.0.0.0/8");
        IpWhitelistingInterceptor interceptor = new IpWhitelistingInterceptor(allowedIps);

        ServerCall<String, String> call = createMockServerCall("10.50.60.70");
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
        ServerCall.Listener<String> mockListener = mock(ServerCall.Listener.class);
        when(next.startCall(any(), any())).thenReturn(mockListener);

        // Execute
        ServerCall.Listener<String> result = interceptor.interceptCall(call, new Metadata(), next);

        // Verify
        assertNotNull(result);
        verify(next, times(1)).startCall(any(), any());
        verify(call, never()).close(any(), any());
    }

    @Test
    void testEmptyWhitelistAllowsAll() {
        // Setup
        List<String> allowedIps = List.of();
        IpWhitelistingInterceptor interceptor = new IpWhitelistingInterceptor(allowedIps);

        ServerCall<String, String> call = createMockServerCall("203.0.113.1");
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
        ServerCall.Listener<String> mockListener = mock(ServerCall.Listener.class);
        when(next.startCall(any(), any())).thenReturn(mockListener);

        // Execute
        ServerCall.Listener<String> result = interceptor.interceptCall(call, new Metadata(), next);

        // Verify - empty whitelist should allow all
        assertNotNull(result);
        verify(next, times(1)).startCall(any(), any());
        verify(call, never()).close(any(), any());
    }

    @Test
    void testWildcardAllowsAll() {
        // Setup
        List<String> allowedIps = List.of("*");
        IpWhitelistingInterceptor interceptor = new IpWhitelistingInterceptor(allowedIps);

        ServerCall<String, String> call = createMockServerCall("203.0.113.1");
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);
        ServerCall.Listener<String> mockListener = mock(ServerCall.Listener.class);
        when(next.startCall(any(), any())).thenReturn(mockListener);

        // Execute
        ServerCall.Listener<String> result = interceptor.interceptCall(call, new Metadata(), next);

        // Verify
        assertNotNull(result);
        verify(next, times(1)).startCall(any(), any());
        verify(call, never()).close(any(), any());
    }

    /**
     * Helper method to create a mock ServerCall with a specific IP address.
     */
    private ServerCall<String, String> createMockServerCall(String ipAddress) {
        @SuppressWarnings("unchecked")
        ServerCall<String, String> call = mock(ServerCall.class);

        // Mock the method descriptor
        @SuppressWarnings("unchecked")
        MethodDescriptor<String, String> methodDescriptor = mock(MethodDescriptor.class);
        when(methodDescriptor.getFullMethodName()).thenReturn("test.Service/TestMethod");
        when(call.getMethodDescriptor()).thenReturn(methodDescriptor);

        // Mock the attributes with IP address
        InetSocketAddress remoteAddr = new InetSocketAddress(ipAddress, 12345);
        Attributes attributes = Attributes.newBuilder()
                .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddr)
                .build();
        when(call.getAttributes()).thenReturn(attributes);

        return call;
    }
}
