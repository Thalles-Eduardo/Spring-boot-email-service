package br.com.thalleseduardo.springemail.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() throws IOException {
        filter = new RateLimitFilter(2, 60);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    }

    @Test
    void allowsUpToLimitThenReturns429() throws ServletException, IOException {
        filter.doFilterInternal(request, response, filterChain);
        filter.doFilterInternal(request, response, filterChain);
        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, times(2)).doFilter(request, response);
        verify(response).setStatus(429);
    }
}
