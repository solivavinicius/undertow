/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.servlet.deployment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.blocking.BlockingHandler;
import io.undertow.server.handlers.blocking.BlockingHttpHandler;
import io.undertow.servlet.UndertowServletMessages;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.FilterInfo;
import io.undertow.servlet.api.InstanceFactory;
import io.undertow.servlet.api.InstanceHandle;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.handlers.DefaultServlet;
import io.undertow.servlet.handlers.FilterHandler;
import io.undertow.servlet.handlers.ManagedFilter;
import io.undertow.servlet.handlers.ServletHandler;
import io.undertow.servlet.handlers.ServletInitialHandler;
import io.undertow.servlet.handlers.ServletMatchingHandler;
import io.undertow.servlet.spec.ServletContextImpl;

/**
 * The deployment manager. This manager is responsible for controlling the lifecycle of a servlet deployment.
 *
 * @author Stuart Douglas
 */
public class DeploymentManagerImpl implements DeploymentManager {

    private final DeploymentInfo deployment;
    private final PathHandler pathHandler;
    private volatile State state = State.UNDEPLOYED;

    public DeploymentManagerImpl(final DeploymentInfo deployment, final PathHandler pathHandler) {
        this.deployment = deployment;
        this.pathHandler = pathHandler;
    }

    @Override
    public void deploy() {

        ClassLoader old = SecurityActions.getContextClassLoader();

        //TODO: this is just a temporary hack, this will probably change a lot
        try {
            SecurityActions.setContextClassLoader(deployment.getClassLoader());

            final ServletContextImpl servletContext = new ServletContextImpl();

            final ServletMatchingHandler servletHandler = setupServletChains(servletContext);
            pathHandler.addPath(deployment.getContextName(), servletHandler);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            SecurityActions.setContextClassLoader(old);
        }
    }

    /**
     * Sets up the handlers in the servlet chain. We setup a chain for every path + extension match possibility.
     * (i.e. if there a m path mappings and n extension mappings we have n*m chains).
     * <p/>
     * If a chain consists of only the default servlet then we add it as an async handler, so that resources can be
     * served up directly without using blocking operations.
     *
     * @param servletContext
     * @throws ClassNotFoundException
     */
    private ServletMatchingHandler setupServletChains(final ServletContextImpl servletContext) throws ClassNotFoundException {

        //create the default servlet
        HttpHandler defaultHandler = null;
        ServletHandler defaultServlet = null;


        final ServletMatchingHandler servletHandler = new ServletMatchingHandler(defaultHandler);

        final Map<FilterInfo, ManagedFilter> managedFilterMap = new LinkedHashMap<FilterInfo, ManagedFilter>();
        final Map<ServletInfo, ServletHandler> servletHandlerMap = new LinkedHashMap<ServletInfo, ServletHandler>();
        final Map<String, ServletInfo> extensionServlets = new HashMap<String, ServletInfo>();
        final Map<String, ServletInfo> pathServlets = new HashMap<String, ServletInfo>();


        final Set<String> pathMatches = new HashSet<String>();
        final Set<String> extensionMatches = new HashSet<String>();

        for (Map.Entry<String, FilterInfo> entry : deployment.getFilters().entrySet()) {
            final Class<?> filterClass = Class.forName(entry.getValue().getFilterClass(), false, deployment.getClassLoader());
            final ManagedFilter mf = new ManagedFilter(entry.getValue(), filterClass);
            managedFilterMap.put(entry.getValue(), mf);
            for (String path : entry.getValue().getMappings()) {
                if (!path.startsWith("*.")) {
                    pathMatches.add(path);
                } else {
                    extensionMatches.add(path.substring(2));
                }
            }
        }

        for (Map.Entry<String, ServletInfo> entry : deployment.getServlets().entrySet()) {
            ServletInfo servlet = entry.getValue();
            Class<?> servletClass = Class.forName(servlet.getServletClass(), false, deployment.getClassLoader());
            final ServletHandler handler = new ServletHandler(servlet, servletClass, servletContext);
            servletHandlerMap.put(servlet, handler);
            for (String path : entry.getValue().getMappings()) {
                if (path.equals("/")) {
                    //the default servlet
                    defaultServlet = handler;
                    defaultHandler = servletChain(handler);
                } else if (!path.startsWith("*.")) {
                    pathMatches.add(path);
                    if (pathServlets.containsKey(path)) {
                        throw UndertowServletMessages.MESSAGES.twoServletsWithSameMapping(path);
                    }
                    pathServlets.put(path, entry.getValue());
                } else {
                    String ext = path.substring(2);
                    extensionMatches.add(ext);
                    extensionServlets.put(ext, entry.getValue());
                }
            }
        }

        if (defaultServlet == null) {
            defaultHandler = new DefaultServlet(deployment.getResourceLoader());
            final HttpHandler handler = defaultHandler;
            defaultServlet = new ServletHandler(ServletInfo.builder().setServletClass(DefaultServlet.class.getName())
                    .setName("DefaultServlet")
                    .setInstanceFactory(new InstanceFactory() {
                        @Override
                        public InstanceHandle createInstance() {
                            return new InstanceHandle() {
                                @Override
                                public Object getInstance() {
                                    return handler;
                                }

                                @Override
                                public void release() {

                                }
                            };
                        }
                    })
                    .build(), DefaultServlet.class, servletContext);
        }

        for (final String path : pathMatches) {
            ServletInfo targetServlet = resolveServletForPath(path, pathServlets);

            final List<ManagedFilter> noExtension = new ArrayList<ManagedFilter>();
            final Map<String, List<ManagedFilter>> extension = new HashMap<String, List<ManagedFilter>>();
            for (String ext : extensionMatches) {
                extension.put(ext, new ArrayList<ManagedFilter>());
            }

            for (Map.Entry<FilterInfo, ManagedFilter> filter : managedFilterMap.entrySet()) {
                for (final String mapping : filter.getKey().getMappings()) {
                    if (path.length() == 0) {
                        if (isFilterApplicable(path, "/*")) {
                            noExtension.add(filter.getValue());
                            for (List<ManagedFilter> l : extension.values()) {
                                l.add(filter.getValue());
                            }
                        }
                    } else if (!path.startsWith("*.")) {
                        if (isFilterApplicable(path, mapping)) {
                            noExtension.add(filter.getValue());
                            for (List<ManagedFilter> l : extension.values()) {
                                l.add(filter.getValue());
                            }
                        }
                    } else {
                        extension.get(path.substring(2)).add(filter.getValue());
                    }
                }
            }
            ServletMatchingHandler.PathMatch pathMatch;

            if (noExtension.isEmpty()) {
                if (targetServlet != null) {
                    pathMatch = new ServletMatchingHandler.PathMatch(servletChain(servletHandlerMap.get(targetServlet)));
                } else {
                    pathMatch = new ServletMatchingHandler.PathMatch(defaultHandler);
                }
            } else {
                FilterHandler handler;
                if (targetServlet != null) {
                    handler = new FilterHandler(noExtension, servletHandlerMap.get(targetServlet));
                } else {
                    handler = new FilterHandler(noExtension, defaultServlet);
                }
                pathMatch = new ServletMatchingHandler.PathMatch(servletChain(handler));
            }

            for (Map.Entry<String, List<ManagedFilter>> entry : extension.entrySet()) {
                ServletInfo pathServlet = targetServlet;
                if (targetServlet == null) {
                    pathServlet = extensionServlets.get(entry.getKey());
                }
                if (entry.getValue().isEmpty()) {
                    if (pathServlet != null) {
                        pathMatch = new ServletMatchingHandler.PathMatch(servletChain(servletHandlerMap.get(pathServlet)));
                    } else {
                        pathMatch = new ServletMatchingHandler.PathMatch(defaultHandler);
                    }
                } else {
                    FilterHandler handler;
                    if (pathServlet != null) {
                        handler = new FilterHandler(entry.getValue(), servletHandlerMap.get(pathServlet));
                    } else {
                        handler = new FilterHandler(entry.getValue(), defaultServlet);
                    }
                    pathMatch.getExtensionMatches().put(entry.getKey(), servletChain(handler));
                }
            }
            if (path.endsWith("/*")) {
                servletHandler.getPrefixMatches().put(path.substring(0, path.length() - 2), pathMatch);
            } else if (path.isEmpty()) {
                servletHandler.getExactPathMatches().put("/", pathMatch);
            } else {
                servletHandler.getExactPathMatches().put(path, pathMatch);
            }
        }

        servletHandler.setDefaultHandler(defaultHandler);
        return servletHandler;
    }

    private static BlockingHandler servletChain(BlockingHttpHandler next) {
        return new BlockingHandler(new ServletInitialHandler(next));
    }

    private ServletInfo resolveServletForPath(final String path, final Map<String, ServletInfo> pathServlets) {
        if (pathServlets.containsKey(path)) {
            return pathServlets.get(path);
        }
        String match = null;
        ServletInfo servlet = null;
        for (final Map.Entry<String, ServletInfo> entry : pathServlets.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("/*")) {
                final String base = key.substring(0, key.length() - 2);
                if (match == null || base.length() > match.length()) {
                    if (path.startsWith(base)) {
                        match = base;
                        servlet = entry.getValue();
                    }
                }
            }
        }
        return servlet;
    }

    private boolean isFilterApplicable(final String path, final String filterPath) {
        if (filterPath.endsWith("*")) {
            String baseFilterPath = filterPath.substring(0, filterPath.length() - 1);
            return path.startsWith(baseFilterPath);
        } else {
            return filterPath.equals(path);
        }
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {

    }

    @Override
    public void undeploy() {

    }

    @Override
    public State getState() {
        return state;
    }
}