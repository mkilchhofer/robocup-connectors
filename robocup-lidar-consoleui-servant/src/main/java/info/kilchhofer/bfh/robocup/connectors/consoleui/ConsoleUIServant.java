package info.kilchhofer.bfh.robocup.connectors.consoleui;

import ch.quantasy.mqtt.gateway.client.ConnectionStatus;
import ch.quantasy.mqtt.gateway.client.GatewayClient;
import ch.quantasy.mqtt.gateway.client.message.MessageReceiver;
import info.kilchhofer.bfh.robocup.connectors.consoleui.binding.ConsoleUIServantContract;
import info.kilchhofer.bfh.robocup.consoleui.service.binding.*;
import info.kilchhofer.bfh.robocup.lidar.service.binding.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static java.lang.Character.toLowerCase;

public class ConsoleUIServant {

    private static final Logger LOGGER = LogManager.getLogger(ConsoleUIServant.class);
    private LidarServiceContract lidarServiceContract;
    private Set<ConsoleUIServiceContract> uiInstances;
    private final GatewayClient<ConsoleUIServantContract> gatewayClient;
    private MessageReceiver keyPressReceiver, uiConnectionStatusReceiver,
            measurementReceiver, tim55xStateReceiver, lidarConnectionStatusReceiver;

    public ConsoleUIServant(URI mqttURI, String mqttClientName, String instanceName) throws MqttException {
        this.gatewayClient = new GatewayClient<>(mqttURI, mqttClientName, new ConsoleUIServantContract(instanceName));
        this.uiInstances = new HashSet<>();
        this.gatewayClient.connect();

        setupUIMessageReceivers();
        setupLidarMessageReceiver();

        this.gatewayClient.subscribe(new ConsoleUIServiceContract("+").STATUS_CONNECTION, this.uiConnectionStatusReceiver);
        this.gatewayClient.subscribe(new LidarServiceContract("+").STATUS_CONNECTION, this.lidarConnectionStatusReceiver);
    }

    /**
     * Configure the MessageReceivers for handling events from ConsoleUI-Service
     */
    private void setupUIMessageReceivers() {

        this.keyPressReceiver = new MessageReceiver() {
            @Override
            public void messageReceived(String topic, byte[] payload) throws Exception {
                Set<ConsoleKeyPressEvent> consoleKeyPressEvents = gatewayClient.toMessageSet(payload, ConsoleKeyPressEvent.class);
                for (ConsoleKeyPressEvent consoleKeyPressEvent : consoleKeyPressEvents) {
                    LOGGER.trace("Event Payload: " + consoleKeyPressEvent.character);
                    LidarIntent lidarIntent = new LidarIntent();

                    Character receivedChar = toLowerCase(consoleKeyPressEvent.character);
                    // Mapping KeyPress (UI) <--> Lidar features
                    switch (receivedChar) {
                        case 's':
                            lidarIntent.command = LidarCommand.SINGLE_MEASUREMENT;
                            break;
                        case 'e':
                            lidarIntent.command = LidarCommand.START_CONTINUOUS_MEASUREMENT;
                            break;
                        case 'd':
                            lidarIntent.command = LidarCommand.STOP_CONTINUOUS_MEASUREMENT;
                            break;
                        default:
                            LOGGER.warn("Received unknown command '{}'", receivedChar);

                    }
                    if (lidarIntent.command != null) {
                        LOGGER.info("Received '{}'. Send Intent '{}' to Hardware Service.",
                                receivedChar,
                                lidarIntent.command.toString());
                        gatewayClient.readyToPublish(lidarServiceContract.INTENT, lidarIntent);
                    }
                }
            }
        };

        this.uiConnectionStatusReceiver = new MessageReceiver() {
            @Override
            public void messageReceived(String topic, byte[] payload) throws Exception {
                LOGGER.trace("uiConnectionStatusReceiver Payload: " + new String(payload));
                ConnectionStatus connectionStatus = gatewayClient.toMessageSet(payload, ConnectionStatus.class).last();
                ConsoleUIServiceContract uiInstanceContract = new ConsoleUIServiceContract(topic, true);

                LOGGER.info("Instance '{}' is now {}", uiInstanceContract.INSTANCE, connectionStatus.value);

                if (connectionStatus.value.equals("online")) {
                    gatewayClient.readyToPublish(uiInstanceContract.INTENT,
                        new ConsoleIntent("This is a message from the Servant, we now have a connection together.")
                    );

                    uiInstances.add(uiInstanceContract);
                    gatewayClient.subscribe(uiInstanceContract.EVENT_KEYPRESS, keyPressReceiver);
                } else {
                    uiInstances.remove(uiInstanceContract);
                    gatewayClient.unsubscribe(uiInstanceContract.EVENT_KEYPRESS);
                }

                // Log Current subscriptions
                Set<String> tmp = gatewayClient.getSubscriptionTopics()
                        .stream()
                        .filter(s -> s.contains(uiInstanceContract.KEYPRESS))
                        .collect(Collectors.toSet());
                LOGGER.info("'{}' active KeyPress subscription(s): {}", tmp.size(), tmp);
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

                    for (ConsoleUIServiceContract instance : uiInstances) {
                        ConsoleIntent consoleIntent = new ConsoleIntent();

                        consoleIntent.consoleMessage = String.format("New Data received (Timestamp = %s): \n-------------\n",
                                lidarMeasurementEvent.getTimeStamp());

                        for (Measurement measurement : lidarMeasurementEvent.getMeasurements()) {
                            consoleIntent.consoleMessage += String.format("Angle = %s; Distance = %d; RSSI = %d\n",
                                    measurement.angle,
                                    measurement.distance,
                                    measurement.rssi);
                        }
                        gatewayClient.readyToPublish(instance.INTENT, consoleIntent);
                    }
                }
            }
        };

        this.tim55xStateReceiver = new MessageReceiver() {
            @Override
            public void messageReceived(String topic, byte[] payload) throws Exception {
                LOGGER.trace("STATUS_STATE Payload: " + payload);
                Set<LidarState> lidarStates = gatewayClient.toMessageSet(payload, LidarState.class);
                for (LidarState lidarState : lidarStates) {

                    for (ConsoleUIServiceContract instance : uiInstances) {
                        gatewayClient.readyToPublish(instance.INTENT,
                                new ConsoleIntent("Sensor " + lidarState.state)
                        );
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
                    gatewayClient.subscribe(lidarServiceContract.STATUS_STATE, tim55xStateReceiver);
                } else {
                    gatewayClient.unsubscribe(lidarServiceContract.EVENT_MEASUREMENT);
                    gatewayClient.unsubscribe(lidarServiceContract.STATUS_STATE);
                }
            }
        };
    }
}
