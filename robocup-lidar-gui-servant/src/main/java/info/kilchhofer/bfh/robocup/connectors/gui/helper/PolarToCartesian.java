package info.kilchhofer.bfh.robocup.connectors.gui.helper;

import info.kilchhofer.bfh.robocup.gui.service.binding.CartesianPoint;
import info.kilchhofer.bfh.robocup.lidar.service.binding.Measurement;

/**
 * @author simon.buehlmann
 * refactored mkilchhofer
 */
public class PolarToCartesian {
    public static CartesianPoint calculate(Measurement measurement) {
        return new CartesianPoint(calcX(measurement), calcY(measurement), measurement.rssi);
    }

    private static int calcX(Measurement measurement) {
        double cos = Math.cos(Math.toRadians(measurement.angle));

        double tempReturn = (cos * measurement.distance);
        return (int) tempReturn;
    }

    private static int calcY(Measurement measurement) {
        double sin = Math.sin(Math.toRadians(measurement.angle));

        double tempReturn = (sin * measurement.distance);
        return (int) tempReturn;
    }
}
