package org.techbd.service.http.hub.prime.poll;
import java.io.File;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.integration.annotation.InboundChannelAdapter;
import org.springframework.integration.annotation.Poller;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

@Component
public class SftpFileHandler {

    @Autowired
    private ApiService apiService;

    @InboundChannelAdapter(channel = "sftpChannel", poller = @Poller(fixedDelay = "500"))
    public Message<File> handleNewFiles() {
        // Spring Integration handles file delivery; this method ensures the channel is polled

        return null;
    }

    @ServiceActivator(inputChannel = "sftpChannel")
    public void processFile(File file) {
        if (file.getName().endsWith(".zip")) {
            System.out.println("Processing file: " + file.getName());
            apiService.sendFile(file);
        }
    }
}
