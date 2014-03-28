package com.devicehive.client.impl.context;


import com.devicehive.client.impl.rest.subs.RestSubManager;
import com.devicehive.client.impl.util.CommandsHandler;
import com.devicehive.client.impl.util.NotificationsHandler;
import com.devicehive.client.impl.util.connection.ConnectionEstablishedNotifier;
import com.devicehive.client.impl.util.connection.ConnectionLostNotifier;
import com.devicehive.client.impl.websocket.WebsocketSubManager;
import com.devicehive.client.model.ApiInfo;
import com.devicehive.client.model.Role;
import com.devicehive.client.model.exceptions.HiveException;
import com.devicehive.client.model.exceptions.InternalHiveClientException;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.HttpMethod;
import java.io.IOException;
import java.net.URI;
import java.sql.Timestamp;
import java.util.UUID;

import static com.devicehive.client.impl.context.Constants.REQUIRED_VERSION_OF_API;

/**
 * Entity that keeps all state, i.e. rest and websocket client, subscriptions info, transport to use.
 */
public class HiveContext implements AutoCloseable {
    private static Logger logger = LoggerFactory.getLogger(HiveContext.class);
    private final HiveRestClient hiveRestClient;
    private final RestSubManager restSubManager;
    private final HiveWebSocketClient hiveWebSocketClient;
    private final WebsocketSubManager websocketSubManager;
    private final CommandsHandler commandsHandler;
    private final NotificationsHandler notificationsHandler;
    private Subscription lastCommandSubscription;
    private Subscription lastNotificationSubscription;
    private HivePrincipal hivePrincipal;

    /**
     * Constructor. Creates rest client or websocket client based on specified transport. If this transport is not
     * available and it is not REST_ONLY switches to another one.
     *
     * @param activateWebsockets
     * @param rest                          RESTful service URL
     * @param role                          auth. level
     * @param connectionEstablishedNotifier notifier for successful reconnection completion
     * @param connectionLostNotifier        notifier for lost connection
     * @param commandsHandler               handler for incoming commands and command updates
     * @param notificationsHandler          handler for incoming notifications
     */
    public HiveContext(boolean activateWebsockets,
                       URI rest,
                       Role role,
                       ConnectionEstablishedNotifier connectionEstablishedNotifier,
                       ConnectionLostNotifier connectionLostNotifier,
                       CommandsHandler commandsHandler,
                       NotificationsHandler notificationsHandler) throws HiveException {
        try {
            this.commandsHandler = commandsHandler;
            this.notificationsHandler = notificationsHandler;
            this.hiveRestClient = new HiveRestClient(rest, this, connectionEstablishedNotifier, connectionLostNotifier);
            ApiInfo info = hiveRestClient.execute("/info", HttpMethod.GET, null, ApiInfo.class, null);
            if (!info.getApiVersion().equals(REQUIRED_VERSION_OF_API)) {
                throw new InternalHiveClientException("incompatible version of device hive server API!");
            }

            URI websocket = websocketUriBuilder(info.getWebSocketServerUrl(), role);
            this.hiveWebSocketClient = activateWebsockets ? new HiveWebSocketClient(websocket, this) : null;

            restSubManager = new RestSubManager(this);

            websocketSubManager = new WebsocketSubManager(this);
        } catch (HiveException ex) {
            close();
            throw ex;
        } catch (Exception ex) {
            close();
            throw new HiveException("Error creating Hive Context. Incorrect URL.", ex);
        }
    }

    public Subscription getLastCommandSubscription() {
        return lastCommandSubscription;
    }

    public void setLastCommandSubscription(Subscription lastCommandSubscription) {
        this.lastCommandSubscription = lastCommandSubscription;
    }

    public Subscription getLastNotificationSubscription() {
        return lastNotificationSubscription;
    }

    public void setLastNotificationSubscription(Subscription lastNotificationSubscription) {
        this.lastNotificationSubscription = lastNotificationSubscription;
    }

    /**
     * @return true if websocket transport is available and should be used, false otherwise
     */
    public boolean isWebsocketSupported() {
        return hiveWebSocketClient != null;
    }

    /**
     * Implementation of close method in Closeable interface. Kills all subscriptions tasks and rest and websocket
     * clients.
     *
     * @throws IOException
     */
    @Override
    public synchronized void close() {
        try {
            if (websocketSubManager != null) {
                websocketSubManager.close();
            }
        } catch (Exception ex) {
            logger.error("Error closing Websocket subscriptions", ex);
        }
        try {
            if (restSubManager != null) {
                restSubManager.close();
            }
        } catch (Exception ex) {
            logger.error("Error closing REST subscriptions", ex);
        }
        try {
            if (hiveWebSocketClient != null) {
                hiveWebSocketClient.close();
            }
        } catch (Exception ex) {
            logger.error("Error closing Websocket client", ex);
        }
        try {
            if (hiveRestClient != null) {
                hiveRestClient.close();
            }
        } catch (Exception ex) {
            logger.error("Error closing REST client", ex);
        }
    }

    public RestSubManager getRestSubManager() {
        return restSubManager;
    }

    public WebsocketSubManager getWebsocketSubManager() {
        return websocketSubManager;
    }

    /**
     * Get rest client.
     *
     * @return rest client.
     */
    public HiveRestClient getHiveRestClient() {
        return hiveRestClient;
    }

    /**
     * Get websocket client.
     *
     * @return websocket client
     */
    public HiveWebSocketClient getHiveWebSocketClient() {
        return hiveWebSocketClient;
    }

    /**
     * Get hive principal (credentials storage).
     *
     * @return hive principal
     */
    public synchronized HivePrincipal getHivePrincipal() {
        return hivePrincipal;
    }

    /**
     * Set hive principal if no one set yet.
     *
     * @param hivePrincipal hive principal with credentials.
     */
    public synchronized void setHivePrincipal(HivePrincipal hivePrincipal) {
        if (this.hivePrincipal != null) {
            throw new IllegalStateException("Principal is already set");
        }
        this.hivePrincipal = hivePrincipal;
    }

    /**
     * Get API info from server
     *
     * @return API info
     */
    public ApiInfo getInfo() throws HiveException {
        String restUrl = null;
        if (isWebsocketSupported()) {
            JsonObject request = new JsonObject();
            request.addProperty("action", "server/info");
            String requestId = UUID.randomUUID().toString();
            request.addProperty("requestId", requestId);
            ApiInfo apiInfo = this.hiveWebSocketClient.sendMessage(request, "info", ApiInfo.class, null);
            restUrl = apiInfo.getRestServerUrl();
        }
        ApiInfo apiInfo = hiveRestClient.execute("/info", HttpMethod.GET, null, ApiInfo.class, null);
        apiInfo.setRestServerUrl(restUrl);
        return apiInfo;
    }

    public Timestamp getServerTimestamp() throws HiveException {
        ApiInfo apiInfo = hiveRestClient.execute("/info", HttpMethod.GET, null, ApiInfo.class, null);
        return apiInfo.getServerTimestamp();
    }

    public String getServerApiVersion() throws HiveException {
        ApiInfo apiInfo = hiveRestClient.execute("/info", HttpMethod.GET, null, ApiInfo.class, null);
        return apiInfo.getApiVersion();
    }

    public CommandsHandler getCommandsHandler() {
        return commandsHandler;
    }

    public NotificationsHandler getNotificationsHandler() {
        return notificationsHandler;
    }

    //Private methods------------------------------------------------------------------------------------------
    private URI websocketUriBuilder(String websocket, Role role) {
        return URI.create(StringUtils.removeEnd(websocket, "/") + role.getWebsocketSubPath());
    }
}
