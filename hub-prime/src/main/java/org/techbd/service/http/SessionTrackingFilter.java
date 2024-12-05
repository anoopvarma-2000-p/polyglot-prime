package org.techbd.service.http;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@Component
public class SessionTrackingFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(SessionTrackingFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest) {
            HttpSession session = httpRequest.getSession(false); // Retrieve existing session, do not create

            if (session != null) {
                handleExistingSession(httpRequest, session);
            } else {
                LOG.info("AV SSN> No existing session for API: {}", httpRequest.getRequestURI());
            }
        }
        chain.doFilter(request, response);
    }

    private void handleExistingSession(HttpServletRequest httpRequest, HttpSession session) {
        String requestUri = httpRequest.getRequestURI();
        LOG.info("AV SSN> Session exists: ID = {}, Accessed API: {}", session.getId(), requestUri);

        // Log previously stored session attributes
        logSessionAttribute(session, "API_ENDPOINT", "Previously associated API");
        logSessionAttribute(session, "HANDLER_CLASS", "Previously associated Handler Class");

        // Update the session with the last accessed API
        session.setAttribute("LAST_ACCESSED_API", requestUri);
    }

    private void logSessionAttribute(HttpSession session, String attributeName, String logMessage) {
        Object attributeValue = session.getAttribute(attributeName);
        if (attributeValue != null) {
            LOG.info("AV SSN> {}: {}", logMessage, attributeValue);
        }
    }
}
