package org.techbd.service.http.hub.prime.poll;

import java.io.File;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.springframework.stereotype.Service;

@Service
public class ApiService {

    public void sendFile(File file) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost("https://synthetic.fhir.api.devl.techbd.org/flatfile/csv/Bundle");

            // Adding headers
            httpPost.addHeader("X-TechBD-Tenant-ID", "AV-INTERNAL-POLL-1");

            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.addBinaryBody("file", file, ContentType.APPLICATION_OCTET_STREAM, file.getName());
            HttpEntity multipart = builder.build();
            httpPost.setEntity(multipart);

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                System.out.println("Response: " + response.getCode() + " " + response.getReasonPhrase());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
