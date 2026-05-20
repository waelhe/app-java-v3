package com.marketplace.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ProblemDetail;

import java.net.URI;

public final class ApiProblemDetails {

    private ApiProblemDetails() {
    }

    public static ProblemDetail fromTaxonomy(ApiErrorTaxonomy taxonomy,
                                             String detail,
                                             HttpServletRequest request,
                                             String userMessage,
                                             String traceId) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(taxonomy.statusCode(), detail);
        pd.setType(URI.create(taxonomy.typeUri()));
        pd.setTitle(taxonomy.title());
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("errorCode", taxonomy.errorCode());
        pd.setProperty("category", taxonomy.category());

        if (userMessage != null && !userMessage.isBlank()) {
            pd.setProperty("userMessage", userMessage);
        }
        if (traceId != null && !traceId.isBlank()) {
            pd.setProperty("traceId", traceId);
        }
        return pd;
    }
}
