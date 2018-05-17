package info.kilchhofer.bfh.lidar.hardwareservice.contract.event;

import ch.quantasy.mqtt.gateway.client.message.AnEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;

public class LidarMeasurementEvent extends AnEvent{
    private static final Logger LOGGER = LogManager.getLogger(LidarMeasurementEvent.class);
    private ArrayList<Measurement> measurements;

    public ArrayList<Measurement> getMeasurements() {
        return measurements;
    }

    public LidarMeasurementEvent(ArrayList<Measurement> measurements) {
        this.measurements = measurements;
    }

    // needed for deserialization
    private LidarMeasurementEvent() {
    }

}