package org.techbd.service.http;

import java.util.List;

import org.jooq.exception.IOException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@ConfigurationProperties(prefix = "server")
public class HostHeaderValidatorFilter implements Filter {

    private List<String> allowedHosts;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException, java.io.IOException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        String hostHeader = httpServletRequest.getHeader("Host");

        if (null != allowedHosts) {
            if (hostHeader != null && !allowedHosts.contains(hostHeader)) {
                ((HttpServletResponse) response).sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid Host Header");
                return;
            }
        } else {
            ((HttpServletResponse) response).sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid settings for allowedHosts");
                return;
        }

        chain.doFilter(request, response);
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }
}
