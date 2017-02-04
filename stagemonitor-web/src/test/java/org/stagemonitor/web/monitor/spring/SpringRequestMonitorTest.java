package org.stagemonitor.web.monitor.spring;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletConfig;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerAdapter;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.stagemonitor.core.CorePlugin;
import org.stagemonitor.core.MeasurementSession;
import org.stagemonitor.core.Stagemonitor;
import org.stagemonitor.core.configuration.Configuration;
import org.stagemonitor.core.elasticsearch.ElasticsearchClient;
import org.stagemonitor.core.metrics.metrics2.Metric2Filter;
import org.stagemonitor.core.metrics.metrics2.Metric2Registry;
import org.stagemonitor.requestmonitor.MockTracer;
import org.stagemonitor.requestmonitor.RequestMonitor;
import org.stagemonitor.requestmonitor.RequestMonitorPlugin;
import org.stagemonitor.requestmonitor.TagRecordingSpanInterceptor;
import org.stagemonitor.web.WebPlugin;
import org.stagemonitor.web.monitor.MonitoredHttpRequest;
import org.stagemonitor.web.monitor.filter.StatusExposingByteCountingServletResponse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;

import io.opentracing.tag.Tags;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.stagemonitor.core.metrics.metrics2.MetricName.name;
import static org.stagemonitor.requestmonitor.BusinessTransactionNamingStrategy.METHOD_NAME_SPLIT_CAMEL_CASE;
import static org.stagemonitor.requestmonitor.reporter.ServerRequestMetricsSpanInterceptor.getTimerMetricName;

public class SpringRequestMonitorTest {

	private MockHttpServletRequest mvcRequest = new MockHttpServletRequest("GET", "/test/requestName");
	private MockHttpServletRequest nonMvcRequest = new MockHttpServletRequest("GET", "/META-INF/resources/stagemonitor/static/jquery.js");
	private Configuration configuration = mock(Configuration.class);
	private RequestMonitorPlugin requestMonitorPlugin = mock(RequestMonitorPlugin.class);
	private WebPlugin webPlugin = mock(WebPlugin.class);
	private CorePlugin corePlugin = mock(CorePlugin.class);
	private RequestMonitor requestMonitor;
	private Metric2Registry registry = new Metric2Registry();
	private HandlerMapping getRequestNameHandlerMapping;
	private DispatcherServlet dispatcherServlet;
	private Map<String, Object> tags = new HashMap<>();

	// the purpose of this class is to obtain a instance to a Method,
	// because Method objects can't be mocked as they are final
	private static class TestController {
		public void testGetRequestName() {
		}
	}

	@Before
	public void before() throws Exception {
		Stagemonitor.reset();
		registry.removeMatching(Metric2Filter.ALL);
		Stagemonitor.getMetric2Registry().removeMatching(Metric2Filter.ALL);
		Stagemonitor.startMonitoring(new MeasurementSession("MonitoredHttpRequestTest", "testHost", "testInstance"));
		getRequestNameHandlerMapping = createHandlerMapping(mvcRequest, TestController.class.getMethod("testGetRequestName"));
		when(configuration.getConfig(RequestMonitorPlugin.class)).thenReturn(requestMonitorPlugin);
		when(configuration.getConfig(WebPlugin.class)).thenReturn(webPlugin);
		when(configuration.getConfig(CorePlugin.class)).thenReturn(corePlugin);
		when(corePlugin.isStagemonitorActive()).thenReturn(true);
		when(corePlugin.getThreadPoolQueueCapacityLimit()).thenReturn(1000);
		when(corePlugin.getMetricRegistry()).thenReturn(registry);
		when(corePlugin.getElasticsearchClient()).thenReturn(mock(ElasticsearchClient.class));
		when(requestMonitorPlugin.isCollectRequestStats()).thenReturn(true);
		when(requestMonitorPlugin.getBusinessTransactionNamingStrategy()).thenReturn(METHOD_NAME_SPLIT_CAMEL_CASE);

		when(webPlugin.getGroupUrls()).thenReturn(Collections.singletonMap(Pattern.compile("(.*).js$"), "*.js"));
		requestMonitor = new RequestMonitor(configuration, registry);


		dispatcherServlet = new DispatcherServlet(new StaticWebApplicationContext());
		dispatcherServlet.init(new MockServletConfig());
		final Field handlerMappings = DispatcherServlet.class.getDeclaredField("handlerMappings");
		handlerMappings.setAccessible(true);
		handlerMappings.set(dispatcherServlet, Collections.singletonList(getRequestNameHandlerMapping));
		final Field handlerAdapters = DispatcherServlet.class.getDeclaredField("handlerAdapters");
		handlerAdapters.setAccessible(true);
		final HandlerAdapter handlerAdapter = mock(HandlerAdapter.class);
		when(handlerAdapter.supports(any())).thenReturn(true);
		handlerAdapters.set(dispatcherServlet, Collections.singletonList(handlerAdapter));

		when(requestMonitorPlugin.getTracer()).thenReturn(RequestMonitorPlugin.getSpanWrappingTracer(new MockTracer(),
				registry, requestMonitorPlugin, requestMonitor, TagRecordingSpanInterceptor.asList(tags)));
		when(requestMonitorPlugin.getRequestMonitor()).thenReturn(requestMonitor);
	}

	private HandlerMapping createHandlerMapping(MockHttpServletRequest request, Method requestMappingMethod) throws Exception {
		System.out.println("createHandlerMapping" + request);
		HandlerMapping requestMappingHandlerMapping = mock(HandlerMapping.class);
		HandlerExecutionChain handlerExecutionChain = mock(HandlerExecutionChain.class);
		HandlerMethod handlerMethod = mock(HandlerMethod.class);

		when(handlerMethod.getMethod()).thenReturn(requestMappingMethod);
		doReturn(TestController.class).when(handlerMethod).getBeanType();
		when(handlerExecutionChain.getHandler()).thenReturn(handlerMethod);
		when(requestMappingHandlerMapping.getHandler(ArgumentMatchers.argThat(item ->
				item.getRequestURI().equals("/test/requestName")))).thenReturn(handlerExecutionChain);
		return requestMappingHandlerMapping;
	}

	@Test
	public void testRequestMonitorMvcRequest() throws Exception {
		when(webPlugin.isMonitorOnlySpringMvcRequests()).thenReturn(false);

		MonitoredHttpRequest monitoredRequest = createMonitoredHttpRequest(mvcRequest);

		final RequestMonitor.RequestInformation requestInformation = requestMonitor.monitor(monitoredRequest);

		assertEquals("Test Get Request Name", requestInformation.getOperationName());
		assertEquals(1, registry.timer(getTimerMetricName(requestInformation.getOperationName())).getCount());
		assertEquals("Test Get Request Name", requestInformation.getOperationName());
		assertEquals("/test/requestName", tags.get(Tags.HTTP_URL.getKey()));
		assertEquals("GET", tags.get("method"));
		Assert.assertNull(requestInformation.getExecutionResult());
		assertNotNull(registry.getTimers().get(name("response_time_server").tag("request_name", "Test Get Request Name").layer("All").build()));
		verify(monitoredRequest, times(1)).onPostExecute(anyRequestInformation());
	}

	@Test
	public void testRequestMonitorNonMvcRequestDoMonitor() throws Exception {
		when(webPlugin.isMonitorOnlySpringMvcRequests()).thenReturn(false);

		final MonitoredHttpRequest monitoredRequest = createMonitoredHttpRequest(nonMvcRequest);

		RequestMonitor.RequestInformation requestInformation = requestMonitor.monitor(monitoredRequest);

		assertEquals("GET *.js", requestInformation.getOperationName());
		assertEquals("GET *.js", requestInformation.getOperationName());
		assertNotNull(registry.getTimers().get(name("response_time_server").tag("request_name", "GET *.js").layer("All").build()));
		assertEquals(1, registry.timer(getTimerMetricName(requestInformation.getOperationName())).getCount());
		verify(monitoredRequest, times(1)).onPostExecute(anyRequestInformation());
		verify(monitoredRequest, times(1)).getRequestName();
	}

	@Test
	public void testRequestMonitorNonMvcRequestDontMonitor() throws Exception {
		when(webPlugin.isMonitorOnlySpringMvcRequests()).thenReturn(true);

		final MonitoredHttpRequest monitoredRequest = createMonitoredHttpRequest(nonMvcRequest);

		RequestMonitor.RequestInformation requestInformation = requestMonitor.monitor(monitoredRequest);

		assertNull(requestInformation.getOperationName());
		assertNull(registry.getTimers().get(name("response_time_server").tag("request_name", "GET *.js").layer("All").build()));
		verify(monitoredRequest, never()).onPostExecute(anyRequestInformation());
	}

	private RequestMonitor.RequestInformation anyRequestInformation() {
		return any();
	}

	private MonitoredHttpRequest createMonitoredHttpRequest(HttpServletRequest request) throws Exception {
		final StatusExposingByteCountingServletResponse response = mock(StatusExposingByteCountingServletResponse.class);
		final FilterChain filterChain = mock(FilterChain.class);
		doAnswer(new Answer() {
			@Override
			public Object answer(InvocationOnMock invocation) throws Throwable {
				dispatcherServlet.service(request, response);
				return null;
			}
		}).when(filterChain).doFilter(any(), any());
		return Mockito.spy(new MonitoredHttpRequest(request, response, filterChain, configuration));
	}

	@Test
	public void testGetRequestNameFromHandler() throws Exception {
		requestMonitor.monitorStart(createMonitoredHttpRequest(mvcRequest));
		final RequestMonitor.RequestInformation requestInformation = RequestMonitor.get().getRequestInformation();
		assertNotNull(requestInformation);
		try {
			dispatcherServlet.service(mvcRequest, new MockHttpServletResponse());
		} finally {
			requestMonitor.monitorStop();
		}
		assertEquals("Test Get Request Name", requestInformation.getOperationName());
	}
}
