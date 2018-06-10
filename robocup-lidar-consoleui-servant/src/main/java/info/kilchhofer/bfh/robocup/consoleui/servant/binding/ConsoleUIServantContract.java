package info.kilchhofer.bfh.robocup.consoleui.servant.binding;

import ch.quantasy.mqtt.gateway.client.contract.AyamlServiceContract;
import ch.quantasy.mqtt.gateway.client.message.Message;

import java.util.Map;

import static info.kilchhofer.bfh.robocup.common.Constants.ROBOCUP_ROOT_CONTEXT;

public class ConsoleUIServantContract extends AyamlServiceContract {

    public ConsoleUIServantContract(String instanceID) {
        super(ROBOCUP_ROOT_CONTEXT, "Lidar-ConsoleUI-Servant", instanceID);
    }

    @Override
    public void setMessageTopics(Map<String, Class<? extends Message>> map) {
        // none
    }
}
