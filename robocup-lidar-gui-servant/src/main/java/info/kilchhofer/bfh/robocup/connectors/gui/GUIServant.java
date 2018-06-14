package info.kilchhofer.bfh.robocup.connectors.gui;

import ch.quantasy.mqtt.gateway.client.ConnectionStatus;
import ch.quantasy.mqtt.gateway.client.GatewayClient;
import ch.quantasy.mqtt.gateway.client.message.MessageReceiver;
import info.kilchhofer.bfh.lidar.edgedetection.binding.EdgeDetectionEvent;
import info.kilchhofer.bfh.lidar.edgedetection.binding.EdgeDetectionServiceContract;
import info.kilchhofer.bfh.lidar.edgedetection.hftm.datahandling.lineExtraction.ExtractedLine;
import info.kilchhofer.bfh.robocup.connectors.gui.binding.GUIServantContract;
import info.kilchhofer.bfh.robocup.connectors.gui.helper.PolarToCartesian;
import info.kilchhofer.bfh.robocup.gui.service.binding.CartesianPoint;
import info.kilchhofer.bfh.robocup.gui.service.binding.GuiIntent;
import info.kilchhofer.bfh.robocup.gui.service.binding.GuiServiceContract;
import info.kilchhofer.bfh.robocup.gui.service.binding.Line;
import info.kilchhofer.bfh.robocup.lidar.service.binding.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.awt.*;
import java.net.URI;
import java.util.*;
import java.util.List;

public class GUIServant {

    private static final Logger LOGGER = LogManager.getLogger(GUIServant.class);
    private LidarServiceContract lidarServiceContract;
    private Set<GuiServiceContract> guiServiceInstances;
    private Set<EdgeDetectionServiceContract> edgeDetectionServiceInstances;
    private Set<LidarServiceContract> tim55xInstances;

    private final GatewayClient<GUIServantContract> gatewayClient;
    private MessageReceiver uiConnectionStatusReceiver, measurementReceiver, lidarConnectionStatusReceiver, edgeDetectionConnectionStatusReceiver, detectedEdgesReceiver;

    public GUIServant(URI mqttURI, String mqttClientName, String instanceName) throws MqttException {
        this.gatewayClient = new GatewayClient<>(mqttURI, mqttClientName, new GUIServantContract(instanceName));
        this.guiServiceInstances = new HashSet<>();
        this.edgeDetectionServiceInstances = new HashSet<>();
        this.tim55xInstances = new HashSet<>();
        this.gatewayClient.connect();

        setupUIMessageReceivers();
        setupLidarMessageReceiver();
        setupEdgeDetectionMessageReceivers();

        this.gatewayClient.subscribe(new EdgeDetectionServiceContract("+").STATUS_CONNECTION, this.edgeDetectionConnectionStatusReceiver);
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
                LOGGER.trace("Gui Status Payload: " + new String(payload));
                ConnectionStatus status = gatewayClient.toMessageSet(payload, ConnectionStatus.class).last();
                GuiServiceContract guiServiceContract = new GuiServiceContract(topic, true);

                LOGGER.info("GuiInstance '{}' is now {}", guiServiceContract.INSTANCE, status.value);

                if (status.value.equals("online")) {
                    guiServiceInstances.add(guiServiceContract);
                } else {
                    guiServiceInstances.remove(guiServiceContract);
                }
                LOGGER.info("'{}' Active GUI Instances", guiServiceInstances.size());
            }
        };
    }

    private void setupEdgeDetectionMessageReceivers(){


        this.detectedEdgesReceiver = new MessageReceiver() {
            @Override
            public void messageReceived(String topic, byte[] payload) throws Exception {
                LOGGER.trace("DetectedEdges Payload: " + new String(payload));
                Set<EdgeDetectionEvent> edgeDetectionEvents = gatewayClient.toMessageSet(payload, EdgeDetectionEvent.class);
                for (EdgeDetectionEvent edgeDetectionEvent : edgeDetectionEvents) {
                    LOGGER.trace("EdgeDetection Event: {}" ,edgeDetectionEvent);

                    List<Line> lines = new ArrayList<>();
                    for (ExtractedLine extractedLine : edgeDetectionEvent.lines) {
                        Point start = new Point(extractedLine.getStartPoint().getX(), extractedLine.getStartPoint().getY());
                        Point stop = new Point(extractedLine.getEndPoint().getX(), extractedLine.getEndPoint().getY());

                        lines.add(new Line(start, stop));
                    }

                    for (GuiServiceContract instance : guiServiceInstances) {
                        LOGGER.info("Publishing '{}' Lines to GUI", lines.size());
                        gatewayClient.readyToPublish(instance.INTENT, new GuiIntent(lines));
                    }
                }
            }
        };


        this.edgeDetectionConnectionStatusReceiver = new MessageReceiver() {
            @Override
            public void messageReceived(String topic, byte[] payload) throws Exception {
                LOGGER.trace("EdgeDetection Status Payload: " + new String(payload));
                ConnectionStatus status = gatewayClient.toMessageSet(payload, ConnectionStatus.class).last();

                EdgeDetectionServiceContract edgeDetectionServiceContractContract = new EdgeDetectionServiceContract(topic, true);

                LOGGER.info("edgeDetectionInstance '{}' is now {}", edgeDetectionServiceContractContract.INSTANCE, status.value);

                // detectedEdgesReceiver

                if (status.value.equals("online")) {
                    LOGGER.info("Subscribing to EdgeDetection Events...");
                    edgeDetectionServiceInstances.add(edgeDetectionServiceContractContract);
                    gatewayClient.subscribe(edgeDetectionServiceContractContract.EVENT_EDGE_DETECTED + "/#", detectedEdgesReceiver);
                } else {
                    LOGGER.info("Unsubscribing to EdgeDetection Events...");
                    edgeDetectionServiceInstances.remove(edgeDetectionServiceContractContract);
                    gatewayClient.unsubscribe(edgeDetectionServiceContractContract.EVENT_EDGE_DETECTED + "/#");
                }
                LOGGER.info("'{}' Active EdgeDetection Instances", edgeDetectionServiceInstances.size());

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

                    List<CartesianPoint> cartesianPoints = new ArrayList<>();
                    for (Measurement measurement : lidarMeasurementEvent.getMeasurements()) {
                        cartesianPoints.add(PolarToCartesian.calculate(measurement));
                    }

                    for (GuiServiceContract instance : guiServiceInstances) {
                        LOGGER.info("Publishing '{}' Points to GUI", cartesianPoints.size());
                        gatewayClient.readyToPublish(instance.INTENT, new GuiIntent("myID", cartesianPoints));
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
                    tim55xInstances.add(lidarServiceContract);
                    gatewayClient.subscribe(lidarServiceContract.EVENT_MEASUREMENT, measurementReceiver);
                } else {
                    tim55xInstances.remove(lidarServiceContract);
                    gatewayClient.unsubscribe(lidarServiceContract.EVENT_MEASUREMENT);
                }
                LOGGER.info("'{}' Active TiM55x Instances", tim55xInstances.size());
            }
        };
    }
}
