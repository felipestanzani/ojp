package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.XaIsSameRMRequest;
import com.openjproxy.grpc.XaIsSameRMResponse;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.openjproxy.grpc.server.Session;
import org.openjproxy.grpc.server.SessionManager;
import org.openjproxy.grpc.server.action.Action;

import java.sql.SQLException;

import static org.openjproxy.grpc.server.GrpcExceptionHandler.sendSQLExceptionMetadata;

/**
 * gRPC action that checks whether two XA sessions are associated with the same
 * underlying
 * resource manager (RM), delegating to
 * {@code javax.transaction.xa.XAResource#isSameRM}.
 *
 * <p>
 * This action resolves both sessions via {@link SessionManager}, validates they
 * are XA-enabled
 * and have an XA resource available, then returns the boolean result to the
 * caller.
 * </p>
 *
 * <p>
 * On failure, the error is reported back to the client as SQL exception
 * metadata.
 * </p>
 */
@Slf4j
public class XaIsSameRMAction implements Action<XaIsSameRMRequest, XaIsSameRMResponse> {

    private final SessionManager sessionManager;

    public XaIsSameRMAction(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Executes the {@code xaIsSameRM} operation for two sessions.
     *
     * <p>
     * The request must contain two valid session identifiers referencing XA
     * sessions. If either
     * session is missing, not XA-enabled, or does not expose an XA resource, an
     * error is returned.
     * </p>
     *
     * @param request          the gRPC request containing {@code session1} and
     *                         {@code session2}
     * @param responseObserver observer used to emit the {@link XaIsSameRMResponse}
     *                         or an error via
     *                         SQL exception metadata
     */
    @Override
    public void execute(XaIsSameRMRequest request, StreamObserver<XaIsSameRMResponse> responseObserver) {
        log.debug("xaIsSameRM: session1={}, session2={}",
                request.getSession1().getSessionUUID(), request.getSession2().getSessionUUID());

        try {
            Session session1 = sessionManager.getSession(request.getSession1());
            Session session2 = sessionManager.getSession(request.getSession2());

            if (session1 == null || !session1.isXA() || session1.getXaResource() == null) {
                throw new SQLException("Session1 is not an XA session");
            }
            if (session2 == null || !session2.isXA() || session2.getXaResource() == null) {
                throw new SQLException("Session2 is not an XA session");
            }

            boolean isSame = session1.getXaResource().isSameRM(session2.getXaResource());

            XaIsSameRMResponse response = XaIsSameRMResponse.newBuilder()
                    .setIsSame(isSame)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Error in xaIsSameRM", e);
            SQLException sqlException = (e instanceof SQLException ex) ? ex : new SQLException(e);
            sendSQLExceptionMetadata(sqlException, responseObserver);
        }
    }
}
