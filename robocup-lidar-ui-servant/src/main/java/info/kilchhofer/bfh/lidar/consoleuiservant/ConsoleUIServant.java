package info.kilchhofer.bfh.lidar.consoleuiservant;

import ch.quantasy.mqtt.gateway.client.ConnectionStatus;
import ch.quantasy.mqtt.gateway.client.GatewayClient;
import info.kilchhofer.bfh.lidar.consoleuiservant.contract.ConsoleUIServantContract;
import info.kilchhofer.bfh.lidar.consoleuiservice.contract.event.ConsoleKeyPressEvent;
import info.kilchhofer.bfh.lidar.consoleuiservice.contract.intent.ConsoleIntent;
import info.kilchhofer.bfh.lidar.hardwareservice.contract.*;
import info.kilchhofer.bfh.lidar.hardwareservice.contract.event.LidarMeasurementEvent;
import info.kilchhofer.bfh.lidar.hardwareservice.contract.event.Measurement;
import info.kilchhofer.bfh.lidar.hardwareservice.contract.intent.LidarCommand;
import info.kilchhofer.bfh.lidar.hardwareservice.contract.intent.LidarIntent;
import info.kilchhofer.bfh.lidar.hardwareservice.contract.status.LidarState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.MqttException;
import info.kilchhofer.bfh.lidar.consoleuiservice.contract.ConsoleUIServiceContract;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static java.lang.Character.toLowerCase;

public class ConsoleUIServant {

    private static final Logger LOGGER = LogManager.getLogger(ConsoleUIServant.class);
    private LidarServiceContract lidarServiceContract;
    private Set<ConsoleUIServiceContract> consoleUIServiceInstances;
    private final GatewayClient<ConsoleUIServantContract> gatewayClient;

    // Do not hardcode topics for autodetect service instances
    private final ConsoleUIServiceContract allConsoleUIContracts = new ConsoleUIServiceContract("+");
    private final LidarServiceContract tempLidarServiceContract = new LidarServiceContract("+");

    public ConsoleUIServant(URI mqttURI, String mqttClientName, String instanceName) throws MqttException {
        this.gatewayClient = new GatewayClient<>(mqttURI, mqttClientName, new ConsoleUIServantContract(instanceName));
        this.consoleUIServiceInstances = new HashSet<>();
        this.gatewayClient.connect();

        handleConsoleUI();
        handleLidarHardware();
    }

    private void handleConsoleUI(){
        // Subscribe to all console UI instances
        this.gatewayClient.subscribe(allConsoleUIContracts.STATUS_CONNECTION, (topic, payload) -> {
            LOGGER.trace("Payload: " + new String(payload));
            ConnectionStatus status = gatewayClient.toMessageSet(payload, ConnectionStatus.class).last();
            ConsoleUIServiceContract consoleUIServiceContract = new ConsoleUIServiceContract(topic, true);

            if (status.value.equals("online")) {
                LOGGER.info("Instance {} online", consoleUIServiceContract.INSTANCE);
                ConsoleIntent consoleIntent = new ConsoleIntent();
                consoleIntent.consoleMessage = "Servant online";

                this.gatewayClient.readyToPublish(consoleUIServiceContract.INTENT, consoleIntent);
                this.consoleUIServiceInstances.add(consoleUIServiceContract);

                this.gatewayClient.subscribe(consoleUIServiceContract.EVENT_KEYPRESS, (eventTopic, eventPayload) -> {

                    Set<ConsoleKeyPressEvent> consoleKeyPressEvents = gatewayClient.toMessageSet(eventPayload, ConsoleKeyPressEvent.class);
                    for (ConsoleKeyPressEvent consoleKeyPressEvent : consoleKeyPressEvents) {
                        LOGGER.trace("Event Payload: " + consoleKeyPressEvent.character);
                        LidarIntent lidarIntent = new LidarIntent();

                        Character receivedChar = toLowerCase(consoleKeyPressEvent.character);
                        switch (receivedChar) {
                            case 's':
                                lidarIntent.command = LidarCommand.SINGLE_MEAS;
                                break;
                            case 'e':
                                lidarIntent.command = LidarCommand.CONT_MEAS_START;
                                break;
                            case 'd':
                                lidarIntent.command = LidarCommand.CONT_MEAS_STOP;
                                break;
                            default:
                                LOGGER.warn("Received unknown command '{}'", receivedChar);

                        }
                        if(lidarIntent.command != null) {
                            LOGGER.info("Received '{}'. Send Intent '{}' to Hardware Service.",
                                    receivedChar,
                                    lidarIntent.command.toString());
                            this.gatewayClient.readyToPublish(lidarServiceContract.INTENT, lidarIntent);
                        }
                    }
                });
            } else {
                LOGGER.info("Instance {} offline", consoleUIServiceContract.INSTANCE);
                consoleUIServiceInstances.remove(consoleUIServiceContract);
            }
        });
    }

    private void handleLidarHardware(){
        // Subscribe to all console UI instances
        this.gatewayClient.subscribe(tempLidarServiceContract.STATUS_CONNECTION, (topic, payload) -> {
            LOGGER.trace("Payload: " + new String(payload));
            ConnectionStatus status = new TreeSet<ConnectionStatus>(gatewayClient.toMessageSet(payload, ConnectionStatus.class)).last();
            this.lidarServiceContract = new LidarServiceContract(topic, true);

            if (status.value.equals("online")) {
                LOGGER.info("Hardware Instance online: {}", lidarServiceContract.INSTANCE);

                // Subscribe to hardware measurement events
                this.gatewayClient.subscribe(lidarServiceContract.EVENT_MEASUREMENT, (eventTopic, eventPayload) ->{
                    Set<LidarMeasurementEvent> lidarMeasurementEvents = gatewayClient.toMessageSet(eventPayload, LidarMeasurementEvent.class);
                    for(LidarMeasurementEvent lidarMeasurementEvent : lidarMeasurementEvents) {

                        for(ConsoleUIServiceContract instance :  consoleUIServiceInstances){
                            ConsoleIntent consoleIntent = new ConsoleIntent();

                            consoleIntent.consoleMessage = String.format("New Data received (Timestamp = %s): \n-------------\n",
                                    lidarMeasurementEvent.getTimeStamp());

                            for(Measurement measurement : lidarMeasurementEvent.getMeasurements()){
                                consoleIntent.consoleMessage += String.format("Angle = %s; Distance = %d; RSSI = %d\n",
                                        measurement.angle,
                                        measurement.distance,
                                        measurement.rssi);
                            }

                            this.gatewayClient.readyToPublish(instance.INTENT, consoleIntent);
                        }
                    }
                });

                // Subscribe to hardware status
                this.gatewayClient.subscribe(lidarServiceContract.STATUS_STATE, (statusTopic, statusPayload) -> {
                    LOGGER.trace("STATUS_STATE Payload: " + statusPayload);
                    Set<LidarState> lidarStates = gatewayClient.toMessageSet(statusPayload, LidarState.class);
                    for(LidarState lidarState : lidarStates) {
                        consoleUIServiceInstances.forEach((instance) -> {
                            ConsoleIntent consoleIntent = new ConsoleIntent();
                            consoleIntent.consoleMessage = "Sensor " + lidarState.state;
                            this.gatewayClient.readyToPublish(instance.INTENT, consoleIntent);
                        });
                    }
                });
            } else {
                LOGGER.info("Hardware Instance offline: {}", lidarServiceContract.INSTANCE);
            }
        });
    }
}