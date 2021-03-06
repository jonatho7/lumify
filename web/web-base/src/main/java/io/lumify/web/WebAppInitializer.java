package io.lumify.web;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRegistration;

public class WebAppInitializer implements ServletContextListener {
    public static final String SERVLET_NAME = "lumify";

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {
        ServletContext servletContext = servletContextEvent.getServletContext();
        Router router = new Router(servletContext);
        ServletRegistration.Dynamic servlet = servletContext.addServlet(SERVLET_NAME, router);
        servlet.addMapping("/*");
        servlet.setAsyncSupported(true);
    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {
        // noop
    }
}
