package info.kilchhofer.bfh.robocup.connectors.gui;

import ch.quantasy.mqtt.gateway.client.ConnectionStatus;
import ch.quantasy.mqtt.gateway.client.GatewayClient;
import ch.quantasy.mqtt.gateway.client.message.MessageReceiver;
import info.kilchhofer.bfh.robocup.connectors.gui.binding.GUIServantContract;
import info.kilchhofer.bfh.robocup.connectors.gui.helper.PolarToCartesian;
import info.kilchhofer.bfh.robocup.gui.service.binding.CartesianPoint;
import info.kilchhofer.bfh.robocup.gui.service.binding.GuiIntent;
import info.kilchhofer.bfh.robocup.gui.service.binding.GuiServiceContract;
import info.kilchhofer.bfh.robocup.lidar.service.binding.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.net.URI;
import java.util.*;

public class GUIServant {

    private static final Logger LOGGER = LogManager.getLogger(GUIServant.class);
    private LidarServiceContract lidarServiceContract;
    private Set<GuiServiceContract> guiServiceInstances;
    private final GatewayClient<GUIServantContract> gatewayClient;
    private MessageReceiver uiConnectionStatusReceiver,
            measurementReceiver, lidarConnectionStatusReceiver;

    public GUIServant(URI mqttURI, String mqttClientName, String instanceName) throws MqttException {
        this.gatewayClient = new GatewayClient<>(mqttURI, mqttClientName, new GUIServantContract(instanceName));
        this.guiServiceInstances = new HashSet<>();
        this.gatewayClient.connect();

        setupUIMessageReceivers();
        setupLidarMessageReceiver();

        this.gatewayClient.subscribe(new GuiServiceContract("+").STATUS_CONNECTION, this.uiConnectionStatusReceiver);
        this.gatewayClient.subscribe(new LidarServiceContract("+").STATUS_CONNECTION, this.lidarConnectionStatusReceiver);
    }

    /**
     * Configure the MessageReceivers for handling events from ConsoleUI-Service
     */
    private void setupUIMessageReceivers() {

        this.uiConnectionStatusReceiver = new MessageReceiver() {
            @Override
            public void messageReceived(String topic, byte[] payload) throws Exception {
                LOGGER.trace("Payload: " + new String(payload));
                ConnectionStatus status = gatewayClient.toMessageSet(payload, ConnectionStatus.class).last();
                GuiServiceContract guiServiceContract = new GuiServiceContract(topic, true);

                LOGGER.info("GuiInstance '{}' is now {}", guiServiceContract.INSTANCE, status.value);

                if (status.value.equals("online")) {
                    guiServiceInstances.add(guiServiceContract);
                } else {
                    guiServiceInstances.remove(guiServiceContract);
                }
            }
        };
    }

    /**
     * Configure the MessageReceivers for handling events from TiM55x-Service
     */
    private void setupLidarMessageReceiver() {

        this.measurementReceiver = new MessageReceiver() {
            @Override
            public void messageReceived(String topic, byte[] payload) throws Exception {
                Set<LidarMeasurementEvent> lidarMeasurementEvents = gatewayClient.toMessageSet(payload, LidarMeasurementEvent.class);
                for (LidarMeasurementEvent lidarMeasurementEvent : lidarMeasurementEvents) {

                    for (GuiServiceContract instance : guiServiceInstances) {

                        List<CartesianPoint> cartesianPoints = new ArrayList<>();
                        for (Measurement measurement : lidarMeasurementEvent.getMeasurements()) {
                            cartesianPoints.add(PolarToCartesian.calculate(measurement));
                        }
                        gatewayClient.readyToPublish(instance.INTENT, new GuiIntent("0", cartesianPoints));
                    }
                }
            }
        };

        this.lidarConnectionStatusReceiver = new MessageReceiver() {
            @Override
            public void messageReceived(String topic, byte[] payload) throws Exception {
                LOGGER.trace("Payload TiM55x ConnStatus: " + new String(payload));
                ConnectionStatus status = new TreeSet<ConnectionStatus>(gatewayClient.toMessageSet(payload, ConnectionStatus.class)).last();
                lidarServiceContract = new LidarServiceContract(topic, true);
                LOGGER.info("TiM55x Instance '{}' is now {}", lidarServiceContract.INSTANCE, status.value);

                if (status.value.equals("online")) {
                    gatewayClient.subscribe(lidarServiceContract.EVENT_MEASUREMENT, measurementReceiver);
                } else {
                    gatewayClient.unsubscribe(lidarServiceContract.EVENT_MEASUREMENT);
                }
            }
        };
    }
}
