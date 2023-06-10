package org.onosproject.icsdefault;

import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Dictionary;
import java.util.Properties;

/**
 * An app component of ONOS SDN controller to defend an industrial control system, with properties defined in pom.xml.
 *
 * onos.app.name: org.onosproject.ics
 * onos.app.title: ICS Guard
 * onos.app.origin: Ziyao Wang
 * onos.app.category: Security
 * onos.app.url: https://github.com/wangziyao318/onos-app-ics
 * onos.app.readme: An ONOS App for industrial control system.
 */
@Component(immediate = true,
        service = {IcsForwardingService.class},
        property = {
                /*
                 Required for the app to function correctly.
                 Valid only when you want to tune some properties of ONOS.
                 DO NOT delete this even if it's useless.
                 */
                "someProperty=some String value"
        })
public class IcsForwarding implements IcsForwardingService {

    /*
     Private instance variables are defined here.
     */

    /**
     * For logging into ONOS console.
     */
    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * For registration of this app itself.
     */
    private ApplicationId appId;

    /**
     * Required for the app to function correctly.
     * It corresponds to the property field of the @Component annotation above.
     * DO NOT delete this even if it's useless.
     */
    private String someProperty;

    /**
     * It listens for device availability to initialise flow tables on new available devices.
     */
    private OvSDeviceListener deviceListener;

    /**
     * A collection of currently available devices to filter new available devices.
     */
    private Collection<Device> availableDevices;

    /*
     These parameters can be tuned to improve defence performance.
     */

    /**
     * Priority of normal flow entries that allow traffic between two given ports.
     */
    private static final int NORMAL_FLOW_PRIORITY = 10;

    /**
     * Priority of guard flow entries that drop traffic from a specified IPv4 and with dstTcpPort == 502.
     * GUARD_FLOW_PRIORITY >> NORMAL_FLOW_PRIORITY
     */
    private static final int GUARD_FLOW_PRIORITY = 10000;

    /**
     * Timeout period of guard flow entries measured in seconds.
     */
    private static final int GUARD_FLOW_TIMEOUT = 180;

    /**
     * The cycle of modbus traffic monitor measured in milliseconds.
     */
    private static final int MODBUS_MONITOR_CYCLE = 1000;

    /**
     * The cycle of http traffic monitor measured in milliseconds.
     */
    private static final int HTTP_MONITOR_CYCLE = 1000;

    /**
     * A threshold of number of modbus packets to determine DoS attack per cycle.
     */
    private static final int MODBUS_THRESHOLD_PER_CYCLE = 90;

    /**
     * A threshold of number of http packets to determine DoS attack per cycle.
     */
    private static final int HTTP_THRESHOLD_PER_CYCLE = 90;

    /*
     Services from ONOS subsystems are called here for use.
     */

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService configService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    /**
     * activate() is called upon activation of this app.
     */
    @Activate
    protected void activate() {

        /*
         Register configuration properties and appId, which is necessary for every ONOS app to work.
         */
        configService.registerProperties(getClass());
        appId = coreService.registerApplication("org.onosproject.ics");

        availableDevices = (Collection<Device>) deviceService.getAvailableDevices(Device.Type.SWITCH);

        /*
         Initialise flow tables on all available devices with normal flows
         that allows all traffic and mirror it to ONOS SDN controller.
         */
        initFlows(availableDevices);

        /*
         Listen for new available devices to initialise their flow tables.
         */
        deviceListener = new OvSDeviceListener();
        deviceService.addListener(deviceListener);

        log.info(" ___ ____ ____     ____ _   _   _    ____  ____");
        log.info("|_ _/ ___/ ___|   / ___| | | | / \\  |  _ \\|  _ \\");
        log.info(" | | |   \\___ \\  | |  _| | | |/ _ \\ | |_) | | | |");
        log.info(" | | |___ ___) | | |_| | |_| / ___ \\|  _ <| |_| |");
        log.info("|___\\____|____/   \\____|\\___/_/   \\_\\_| \\_\\____/");
        log.info("Started appId == " + appId.id());
    }

    /**
     * deactivate() is called upon deactivation of this app.
     */
    @Deactivate
    protected void deactivate() {

        /*
         For garbage collection purposes.
         */
        configService.unregisterProperties(getClass(), true);

        flowRuleService.removeFlowRulesById(appId);
        deviceService.removeListener(deviceListener);
        deviceListener = null;
        availableDevices = null;

        log.info(" ___ ____ ____     ____ _   _   _    ____  ____");
        log.info("|_ _/ ___/ ___|   / ___| | | | / \\  |  _ \\|  _ \\");
        log.info(" | | |   \\___ \\  | |  _| | | |/ _ \\ | |_) | | | |");
        log.info(" | | |___ ___) | | |_| | |_| / ___ \\|  _ <| |_| |");
        log.info("|___\\____|____/   \\____|\\___/_/   \\_\\_| \\_\\____/");
        log.info("Stopped appId == " + appId.id());
        appId = null;
    }

    /**
     * modified() is called right after activate(), which is necessary for every ONOS app to preserve its state.
     * DO NOT delete this even if it's useless.
     *
     * @param context component context
     */
    @Modified
    public void modified(ComponentContext context) {

        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = Tools.get(properties, "someProperty");
        }
        log.info("Reconfigured appId == " + appId.id());
    }

    @Override
    public void initFlows(Collection<Device> newAvailableDevices) {

        for (Device device : newAvailableDevices) {

            /*
             Empty the flow table.
             */
            flowRuleService.purgeFlowRules(device.id());

            /*
             Check if the device is OVS-II by finding packet activities in eth3 and eth4.
             OVS-II has eth1-4 connected, while OVS-I and OVS-III only has eth1-2 connected.

             This flow initialisation process is specified to my ICS network topology, so
             you should change the input ports and output ports according to your needs.

             Note that change to bridges or interfaces in OVS would break the mapping
             between port number and physical port (e.g., port 1 <-> eth1).
             So, don't change them unless you know what you are doing.
             */
            if (!deviceService.getStatisticsForPort(device.id(), PortNumber.portNumber(3)).isZero() &&
                    !deviceService.getStatisticsForPort(device.id(), PortNumber.portNumber(4)).isZero()) {
                /*
                 OVS-II allows all traffic between eth1 and eth4, and
                 also all traffic between eth2 and eth3.
                 */
                addNormalFlow(device.id(), 1, 4);
                addNormalFlow(device.id(), 4, 1);
                addNormalFlow(device.id(), 2, 3);
                addNormalFlow(device.id(), 3, 2);
            } else {
                /*
                 OVS-I and OVS-III allow all traffic between eth1 and eth2.
                 */
                addNormalFlow(device.id(), 1, 2);
                addNormalFlow(device.id(), 2, 1);
            }

            log.info("Initialised the flow table on " + device.id());
        }
    }

    @Override
    public void addNormalFlow(DeviceId deviceId, int inPort, int outPort) {
        flowObjectiveService.forward(deviceId, DefaultForwardingObjective.builder()
                .fromApp(appId).makePermanent().withFlag(ForwardingObjective.Flag.SPECIFIC)
                .withPriority(NORMAL_FLOW_PRIORITY)
                .withSelector(DefaultTrafficSelector.builder()
                        .matchInPort(PortNumber.portNumber(inPort))
                        .build())
                .withTreatment(DefaultTrafficTreatment.builder()
                        .setOutput(PortNumber.portNumber(outPort))
                        .setOutput(PortNumber.CONTROLLER)
                        .build())
                .add()
        );
    }

    /**
     * The device listener listens for device availability to initialise flows on new available devices.
     */
    private class OvSDeviceListener implements DeviceListener {
        @Override
        public void event(DeviceEvent event) {

            /*
             A newly-added or newly-available device needs initialisation.
             */
            if (event.type() == DeviceEvent.Type.DEVICE_AVAILABILITY_CHANGED ||
                    event.type() == DeviceEvent.Type.DEVICE_ADDED) {

                Collection<Device> newAvailableDevices = (Collection<Device>) deviceService
                        .getAvailableDevices(Device.Type.SWITCH);
                newAvailableDevices.removeAll(availableDevices);
                availableDevices = (Collection<Device>) deviceService.getAvailableDevices(Device.Type.SWITCH);

                if (!newAvailableDevices.isEmpty()) {
                    initFlows(newAvailableDevices);
                }
            }
        }
    }
}
