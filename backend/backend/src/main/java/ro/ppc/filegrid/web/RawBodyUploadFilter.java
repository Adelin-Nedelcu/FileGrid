package ro.ppc.filegrid.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RawBodyUploadFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !"POST".equals(request.getMethod())
                || !(path.equals("/api/files") || path.equals("/api/meter-readings"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new QueryStringOnlyRequest(request), response);
    }

    private static final class QueryStringOnlyRequest extends HttpServletRequestWrapper {

        private final Map<String, String[]> parameters;

        QueryStringOnlyRequest(HttpServletRequest request) {
            super(request);
            Map<String, String[]> params = new LinkedHashMap<>();
            if (request.getQueryString() != null) {
                MultiValueMap<String, String> raw = UriComponentsBuilder.newInstance()
                        .query(request.getQueryString()).build().getQueryParams();
                raw.forEach((key, values) -> params.put(decode(key),
                        values.stream().map(v -> v == null ? "" : decode(v)).toArray(String[]::new)));
            }
            this.parameters = Collections.unmodifiableMap(params);
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }

        @Override
        public String getParameter(String name) {
            String[] values = parameters.get(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return parameters;
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters.keySet());
        }

        @Override
        public String[] getParameterValues(String name) {
            return parameters.get(name);
        }
    }
}
