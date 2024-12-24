package org.techbd.service.http.hub.prime.poll;

import java.io.File;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.InboundChannelAdapter;
import org.springframework.integration.annotation.Poller;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.file.filters.AcceptAllFileListFilter;
import org.springframework.integration.sftp.filters.SftpSimplePatternFileListFilter;
import org.springframework.integration.sftp.inbound.SftpInboundFileSynchronizer;
import org.springframework.integration.sftp.inbound.SftpInboundFileSynchronizingMessageSource;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.messaging.MessageChannel;

@Configuration
public class SftpConfig {

    @Value("${TECHBD_CSV_POLL_SFTP_SERVER:#{null}}")
    private String host;

    @Value("${TECHBD_CSV_POLL_SFTP_PORT:#{null}}")
    private int port;

    @Value("${TECHBD_CSV_POLL_SFTP_USERNAME:#{null}}")
    private String username;

    @Value("${TECHBD_CSV_POLL_SFTP_PASSWORD:#{null}}")
    private String password;

    @Value("${TECHBD_CSV_POLL_SFTP_REMOTE_DIR:#{null}}")
    private String remoteDirectory;

    @Value("${TECHBD_CSV_POLL_SFTP_LOCAL_DIR:#{null}}")
    private String localdir;

    @Bean
    public DefaultSftpSessionFactory sftpSessionFactory() {
        DefaultSftpSessionFactory factory = new DefaultSftpSessionFactory();
        factory.setHost(host);
        factory.setPort(port);
        factory.setUser(username);
        factory.setPassword(password);
        factory.setAllowUnknownKeys(true); // Use this for testing; otherwise configure known_hosts
        return factory;
    }

    @Bean
    public SftpInboundFileSynchronizer fileSynchronizer() {
        SftpInboundFileSynchronizer synchronizer = new SftpInboundFileSynchronizer(sftpSessionFactory());
        synchronizer.setDeleteRemoteFiles(false);
        synchronizer.setRemoteDirectory(remoteDirectory);
        synchronizer.setFilter(new SftpSimplePatternFileListFilter("*.zip"));
        return synchronizer;
    }

    @Bean
    public MessageChannel sftpChannel() {
        return new DirectChannel();
    }

    @InboundChannelAdapter(channel = "sftpChannel", poller = @Poller(fixedDelay = "5000")) // Poll every 5 seconds
    @Bean
    public MessageSource<File> sftpMessageSource() {
        SftpInboundFileSynchronizingMessageSource source = new SftpInboundFileSynchronizingMessageSource(
                fileSynchronizer());
        source.setLocalDirectory(new File(localdir));
        source.setAutoCreateLocalDirectory(true);
        source.setLocalFilter(new AcceptAllFileListFilter<>());
        return source;
    }

}
