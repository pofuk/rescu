/**
 * Copyright (C) 2013 Matija Mazi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package si.mazi.rescu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.mazi.rescu.serialization.PlainTextResponseReader;
import si.mazi.rescu.serialization.ToStringRequestWriter;
import si.mazi.rescu.serialization.jackson.DefaultJacksonObjectMapperFactory;
import si.mazi.rescu.serialization.jackson.JacksonObjectMapperFactory;
import si.mazi.rescu.serialization.jackson.JacksonRequestWriter;
import si.mazi.rescu.serialization.jackson.JacksonResponseReader;

import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Matija Mazi
 */
public class RestInvocationHandler implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(RestInvocationHandler.class);

    private final ResponseReaderResolver responseReaderResolver;
    private final RequestWriterResolver requestWriterResolver;

    private final HttpTemplate httpTemplate;
    private final String intfacePath;
    private final String baseUrl;
    private ApiContext context;


    private final Map<Method, RestMethodMetadata> methodMetadataCache = new HashMap<>();

    RestInvocationHandler(final Class<?> restInterface, final String url, final ApiContext context) {
        Validate.notNull(restInterface);
        Validate.notNull(url);
        Validate.notNull(context);

        this.intfacePath = restInterface.getAnnotation(Path.class).value();
        this.baseUrl = url;
        this.context = context;

        //setup default readers/writers
        JacksonObjectMapperFactory mapperFactory = context.getClientConfig().getJacksonObjectMapperFactory();
        if (mapperFactory == null) {
            mapperFactory = new DefaultJacksonObjectMapperFactory();
        }
        final ObjectMapper mapper = mapperFactory.createObjectMapper();

        requestWriterResolver = new RequestWriterResolver();
        /*requestWriterResolver.addWriter(null,
                new NullRequestWriter());*/
        requestWriterResolver.addWriter(MediaType.APPLICATION_FORM_URLENCODED,
                new FormUrlEncodedRequestWriter());
        requestWriterResolver.addWriter(MediaType.APPLICATION_JSON,
                new JacksonRequestWriter(mapper));
        requestWriterResolver.addWriter(MediaType.TEXT_PLAIN,
                new ToStringRequestWriter());

        responseReaderResolver = new ResponseReaderResolver();
        responseReaderResolver.addReader(MediaType.APPLICATION_JSON,
                new JacksonResponseReader(mapper, this.context.getClientConfig().isIgnoreHttpErrorCodes()));
        responseReaderResolver.addReader(MediaType.TEXT_PLAIN,
                new PlainTextResponseReader(this.context.getClientConfig().isIgnoreHttpErrorCodes()));

        //setup http client
        this.httpTemplate = new HttpTemplate(
                this.context.getClientConfig().getHttpConnTimeout(),
                this.context.getClientConfig().getHttpReadTimeout(),
                this.context.getClientConfig().getProxyHost(), this.context.getClientConfig().getProxyPort(),
                this.context.getClientConfig().getSslSocketFactory(), this.context.getClientConfig().getHostnameVerifier(), this.context.getClientConfig().getOAuthConsumer());
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
        if (method.getDeclaringClass().equals(Object.class)) {
            return method.invoke(this, args);
        }

        final RestMethodMetadata methodMetadata = getMetadata(method);

        HttpURLConnection connection = null;
        RestInvocation invocation = null;
        Object lock = getValueGenerator(args);
        if (lock == null) {
            lock = new Object(); // effectively no locking
        }
        try {
            synchronized (lock) {
                invocation = RestInvocation.create(
                        requestWriterResolver, methodMetadata, args, context.getClientConfig().getDefaultParamsMap());
                connection = invokeHttp(invocation);
            }
            return receiveAndMap(methodMetadata, connection);
        } catch (final Exception e) {
            boolean shouldWrap = context.getClientConfig().isWrapUnexpectedExceptions();
            if (e instanceof InvocationAware) {
                try {
                    ((InvocationAware) e).setInvocation(invocation);
                    shouldWrap = false;
                } catch (final Exception ex) {
                    log.warn("Failed to set invocation on the InvocationAware", ex);
                }
            }
            if (e instanceof HttpResponseAware && connection != null) {
                try {
                    ((HttpResponseAware) e).setResponseHeaders(connection.getHeaderFields());
                    shouldWrap = false;
                } catch (final Exception ex) {
                    log.warn("Failed to set response headers on the HttpReponseAware", ex);
                }
            }
            if (shouldWrap) {
                throw new AwareException(e, invocation);
            }
            throw e;
        }
    }

    protected HttpURLConnection invokeHttp(final RestInvocation invocation) throws IOException {
        final RestMethodMetadata methodMetadata = invocation.getMethodMetadata();

        final RequestWriter requestWriter = requestWriterResolver.resolveWriter(invocation.getMethodMetadata());
        final String requestBody = requestWriter.writeBody(invocation);

        final Map<String, String> headers = invocation.getAllHttpHeaders();
        headers.putAll(context.getHeaders());
        return httpTemplate.send(invocation.getInvocationUrl(), requestBody,headers , methodMetadata.getHttpMethod());
    }

    protected Object receiveAndMap(final RestMethodMetadata methodMetadata, final HttpURLConnection connection) throws IOException {
        final InvocationResult invocationResult = httpTemplate.receive(connection);
        return mapInvocationResult(invocationResult, methodMetadata);
    }

    private static SynchronizedValueFactory getValueGenerator(final Object[] args) {
        if (args != null) for (final Object arg : args)
            if (arg instanceof SynchronizedValueFactory)
                return (SynchronizedValueFactory) arg;
        return null;
    }

    protected Object mapInvocationResult(final InvocationResult invocationResult,
                                         final RestMethodMetadata methodMetadata) throws IOException {
        return responseReaderResolver.resolveReader(methodMetadata).read(invocationResult, methodMetadata);
    }

    private RestMethodMetadata getMetadata(final Method method) {
        RestMethodMetadata metadata = methodMetadataCache.get(method);
        if (metadata == null) {
            metadata = RestMethodMetadata.create(method, baseUrl, intfacePath);
            methodMetadataCache.put(method, metadata);
        }
        return metadata;
    }
}
