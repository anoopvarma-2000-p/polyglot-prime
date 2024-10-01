package org.techbd.service.http.hub.prime;

import java.util.Map;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.ServletWebRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Controller
public class CustomErrorController implements ErrorController {

    private final ErrorAttributes errorAttributes;

    public CustomErrorController(ErrorAttributes errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    @RequestMapping("/error")
    public String getErrorPage(final Model model, final HttpServletRequest request, HttpServletResponse response) {
        ServletWebRequest webRequest = new ServletWebRequest(request);

        Map<String, Object> errorDetails = errorAttributes.getErrorAttributes(webRequest, ErrorAttributeOptions.defaults());

        // Get error details, including the custom message from sendError
        String status = String.valueOf(response.getStatus());
        String errorMessage = (String) errorDetails.get("message");

        model.addAttribute("status", status);
        model.addAttribute("error", errorDetails.get("error"));
        model.addAttribute("message", errorMessage != null ? errorMessage : "No specific message available");
        
        return "page/error";
    }

    public String getErrorPath() {
        return "/error";
    }
}
