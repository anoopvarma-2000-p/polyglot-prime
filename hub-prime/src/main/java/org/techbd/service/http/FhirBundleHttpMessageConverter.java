package org.techbd.service.http;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.hl7.fhir.r4.model.Bundle;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;

public class FhirBundleHttpMessageConverter implements HttpMessageConverter<Bundle> {

    private final FhirContext fhirContext = FhirContext.forR4();

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return clazz == Bundle.class && 
               (mediaType.isCompatibleWith(MediaType.valueOf("application/fhir+json")) ||
                mediaType.getType().equals("application") && mediaType.getSubtype().startsWith("fhir+json"));
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return false; // We focus only on reading
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
        return Arrays.asList(
            MediaType.valueOf("application/fhir+json"),
            MediaType.valueOf("application/fhir+xml")
        );
    }

    @Override
    public Bundle read(Class<? extends Bundle> clazz, HttpInputMessage inputMessage) throws IOException {
        IParser parser = fhirContext.newJsonParser();
        return (Bundle) parser.parseResource(inputMessage.getBody());
    }

    @Override
    public void write(Bundle bundle, MediaType contentType, org.springframework.http.HttpOutputMessage outputMessage) {
        throw new UnsupportedOperationException("Write not supported");
    }
}
