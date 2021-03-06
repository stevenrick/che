/*
 * Copyright (c) 2012-2018 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.api.workspace.server.hc.probe.server;

import static java.util.Collections.singletonMap;

import java.net.URI;
import javax.inject.Inject;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriBuilderException;
import org.eclipse.che.api.core.model.workspace.runtime.Server;
import org.eclipse.che.api.workspace.server.hc.probe.HttpProbeConfig;
import org.eclipse.che.api.workspace.server.spi.InternalInfrastructureException;
import org.eclipse.che.api.workspace.server.token.MachineTokenException;
import org.eclipse.che.api.workspace.server.token.MachineTokenProvider;

/**
 * Produces {@link HttpProbeConfig} for ws-agent liveness probes.
 *
 * @author Alexander Garagatyi
 */
public class WsAgentServerLivenessProbeConfigFactory implements HttpProbeConfigFactory {
  private final MachineTokenProvider machineTokenProvider;
  private final int successThreshold;

  @Inject
  public WsAgentServerLivenessProbeConfigFactory(
      MachineTokenProvider machineTokenProvider, int successThreshold) {
    this.machineTokenProvider = machineTokenProvider;
    this.successThreshold = successThreshold;
  }

  @Override
  public HttpProbeConfig get(String workspaceId, Server server)
      throws InternalInfrastructureException {

    try {
      // add trailing slash
      URI uri = UriBuilder.fromUri(server.getUrl()).path("/").build();

      int port;
      if (uri.getPort() == -1) {
        if ("http".equals(uri.getScheme())) {
          port = 80;
        } else {
          port = 443;
        }
      } else {
        port = uri.getPort();
      }

      return new HttpProbeConfig(
          port,
          uri.getHost(),
          uri.getScheme(),
          uri.getPath(),
          singletonMap(HttpHeaders.AUTHORIZATION, machineTokenProvider.getToken(workspaceId)),
          successThreshold,
          3,
          120,
          10,
          10);
    } catch (MachineTokenException e) {
      throw new InternalInfrastructureException(
          "Failed to retrieve workspace token for ws-agent server liveness probe. Error: "
              + e.getMessage());
    } catch (UriBuilderException e) {
      throw new InternalInfrastructureException(
          "Wsagent server liveness probe url is invalid. Error: " + e.getMessage());
    }
  }
}
