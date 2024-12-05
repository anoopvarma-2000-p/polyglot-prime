package org.techbd.service.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

@Component
public class CustomSessionListener implements HttpSessionListener {

    private static final Logger logger = LoggerFactory.getLogger(CustomSessionListener.class);

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        logger.info("AV SSN> Session created: " + event.getSession().getId());
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        logger.info("AV SSN> Session destroyed: " + event.getSession().getId());
    }
}
