package io.jenkins.jenkinsfile.runner;

import hudson.ClassicPluginStrategy;
import hudson.security.ACL;
import io.jenkins.jenkinsfile.runner.bootstrap.Bootstrap;
import io.jenkins.jenkinsfile.runner.bootstrap.ClassLoaderBuilder;
import jenkins.slaves.DeprecatedAgentProtocolMonitor;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebXmlConfiguration;

import javax.servlet.ServletContext;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Set up of Jenkins environment for executing a single Jenkinsfile.
 *
 * @author Kohsuke Kawaguchi
 */
public class JenkinsfileRunnerLauncher extends JenkinsEmbedder {
    private final Bootstrap bootstrap;
    /**
     * Keep the reference around to prevent them from getting GCed.
     */
    private final Set<Object> noGc = new HashSet<>();

    public JenkinsfileRunnerLauncher(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    /**
     * Sets up Jetty without any actual TCP port serving HTTP.
     */
    @Override
    protected ServletContext createWebServer() throws Exception {
        QueuedThreadPool queuedThreadPool = new QueuedThreadPool(10);
        server = new Server(queuedThreadPool);

        WebAppContext context = new WebAppContext(bootstrap.warDir.getPath(), contextPath);
        context.setClassLoader(getClass().getClassLoader());
        context.setConfigurations(new Configuration[]{new WebXmlConfiguration()});
        context.addBean(new NoListenerConfiguration(context));
        server.setHandler(context);
        context.getSecurityHandler().setLoginService(configureUserRealm());
        context.setResourceBase(bootstrap.warDir.getPath());

        ServerConnector connector = new ServerConnector(server);
        HttpConfiguration config = connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration();
        config.setRequestHeaderSize(12 * 1024);
        String host = System.getenv("JENKINS_HOST");
        String port = System.getenv("JENKINS_PORT");
        connector.setHost(host);
        connector.setPort(Integer.parseInt(port));
        server.addConnector(connector);

        server.start();

        setPluginManager(new PluginManagerImpl(context.getServletContext(), bootstrap.pluginsDir));

        return context.getServletContext();
    }

    @Override
    public void recipe() throws Exception {
        // Not action needed so far
    }

    /**
     * Supply a dummy {@link LoginService} that allows nobody.
     */
    @Override
    protected LoginService configureUserRealm() {
        return new HashLoginService();
    }

    @Override
    public void before() throws Throwable {
        setLogLevels();
        super.before();
    }

    /**
     * We don't want to clutter console with log messages, so kill of any unimportant ones.
     */
    private void setLogLevels() {
        Logger.getLogger("").setLevel(Level.WARNING);
        // Prevent warnings for plugins with old plugin POM (JENKINS-54425)
        Logger.getLogger(ClassicPluginStrategy.class.getName()).setLevel(Level.SEVERE);
        Logger l = Logger.getLogger(DeprecatedAgentProtocolMonitor.class.getName());
        l.setLevel(Level.OFF);
        noGc.add(l);    // the configuration will be lost if Logger gets GCed.
    }

    /**
     * Skips the clean up.
     *
     * This was initially motivated by SLF4J leaving gnarly messages.
     * The whole JVM is going to die anyway, so we don't really care about cleaning up anything nicely.
     */
    @Override
    public void after() throws Exception {
        jenkins = null;
        super.after();
    }

    //TODO: add support of timeout
    /**
     * Launch the Jenkins instance
     * No time out and no output message
     */
    public int launch() throws Throwable {
        int returnCode = -1;
        Thread t = Thread.currentThread();
        String currentThreadName = t.getName();
        t.setName("Executing "+ env.displayName());
        before();
        try {
            // so that test code has all the access to the system
            ACL.impersonate(ACL.SYSTEM);
            ClassLoader cl = new ClassLoaderBuilder(jenkins.getPluginManager().uberClassLoader)
                    .collectJars(new File(bootstrap.appRepo, "io/jenkins/jenkinsfile-runner/payload"))
                    .make();

            Class<?> c = cl.loadClass("io.jenkins.jenkinsfile.runner.Runner");
            returnCode = (int)c.getMethod("run", Bootstrap.class).invoke(c.newInstance(), bootstrap);
        } finally {
            after();
            t.setName(currentThreadName);
        }

        return returnCode;
    }

}
