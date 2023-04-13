package org.onosproject.ics;

import org.onlab.packet.Data;
import org.onlab.packet.EthType;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.TCP;
import org.onlab.packet.TpPort;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.cli.net.IpProtocol;
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
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
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
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

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
        service = {ReactiveDefenceService.class},
        property = {
                /*
                 Required for the app to function correctly.
                 Valid only when you want to tune some properties of ONOS.
                 DO NOT delete this even if it's useless.
                 */
                "someProperty=some String value"
        })
public class ReactiveDefence implements ReactiveDefenceService {

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
     * It selects and logs all Modbus/TCP packets with "write" commands.
     */
    private PacketSelector packetSelector;

    /**
     * A collection of currently available devices to filter new available devices.
     */
    private Collection<Device> availableDevices;

    /**
     * A thread-safe hashmap to log and count IPv4 addresses of
     * the remote hosts sending Modbus/TCP packets with "write" commands.
     */
    private ConcurrentHashMap<Ip4Address, Integer> remoteHosts;

    /**
     * A thread handler to start and interrupt the runnable traffic monitor.
     */
    private Thread t;

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
    private static final int GUARD_FLOW_TIMEOUT = 30;

    /**
     * The cycle of the traffic monitor measured in milliseconds.
     */
    private static final int MONITOR_CYCLE = 500;

    /**
     * A threshold of number of packets to determine DoS attack per cycle.
     */
    private static final int DOS_THRESHOLD_PER_CYCLE = 5;

    /**
     * A DoS threshold is calculated using two parameters above in pkt/s.
     */
    private static final double DOS_THRESHOLD = DOS_THRESHOLD_PER_CYCLE / (MONITOR_CYCLE / 1000.);

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
    protected PacketService packetService;
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

        remoteHosts = new ConcurrentHashMap<>();
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

        /*
         Select Modbus/TCP packets with "write" commands from all mirrored packets.
         */
        packetSelector = new PacketSelector();
        packetService.addProcessor(packetSelector, PacketProcessor.director(3));

        log.info(" ___ ____ ____     ____ _   _   _    ____  ____");
        log.info("|_ _/ ___/ ___|   / ___| | | | / \\  |  _ \\|  _ \\");
        log.info(" | | |   \\___ \\  | |  _| | | |/ _ \\ | |_) | | | |");
        log.info(" | | |___ ___) | | |_| | |_| / ___ \\|  _ <| |_| |");
        log.info("|___\\____|____/   \\____|\\___/_/   \\_\\_| \\_\\____/");
        log.info("Started appId == " + appId.id());
        log.info("DoS threshold == " + DOS_THRESHOLD + " pkt/s");

        /*
         Start the traffic monitor as a new thread.
         */
        t = new Thread(new ModbusTrafficMonitor());
        t.start();
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

        t.interrupt();
        flowRuleService.removeFlowRulesById(appId);
        packetService.removeProcessor(packetSelector);
        packetSelector = null;
        deviceService.removeListener(deviceListener);
        deviceListener = null;
        availableDevices = null;
        remoteHosts = null;

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

    @Override
    public void addGuardFlow(DeviceId deviceId, Ip4Address ip4) {
        flowObjectiveService.forward(deviceId, DefaultForwardingObjective.builder()
                .fromApp(appId).makeTemporary(GUARD_FLOW_TIMEOUT).withFlag(ForwardingObjective.Flag.SPECIFIC)
                .withPriority(GUARD_FLOW_PRIORITY)
                .withSelector(DefaultTrafficSelector.builder()
                        .matchEthType(EthType.EtherType.IPV4.ethType().toShort())
                        .matchIPSrc(ip4.toIpPrefix())
                        .matchIPProtocol((byte) IpProtocol.TCP.value())
                        .matchTcpDst(TpPort.tpPort(502))
                        .build())
                .withTreatment(DefaultTrafficTreatment.builder()
                        .drop()
                        .build())
                .add()
        );
    }

    /**
     * The packet selector picks and logs all Modbus/TCP packets with "write" commands.
     * Forwarding is not implemented because these packets are just mirrors of normal traffic.
     */
    private class PacketSelector implements PacketProcessor {
        @Override
        public void process(PacketContext context) {

            /*
             Check if packet is handled, which is the case when Reactive Forwarding
             is enabled as a packet processor to forward the traffic
             with a higher priority (2) than ICS guard (3).
             */
            if (context.isHandled()) {
                log.warn("ICS DEFENSE is in beta compatibility with onos-app-fwd");
                log.warn("Please disable Reactive Forwarding for better performance");
            }

            /*
             Ensure an Ethernet packet.
             */
            if (context.inPacket().parsed() == null) {
                return;
            }
            Ethernet ethPkt = context.inPacket().parsed();

            /*
             Ensure a unicast IPv4 packet and retrieve its source IPv4 address.
             */
            if (ethPkt.isMulticast() || !(ethPkt.getPayload() instanceof IPv4)) {
                return;
            }
            IPv4 ip4Pkt = (IPv4) ethPkt.getPayload();
            Ip4Address srcIP4 = Ip4Address.valueOf(ip4Pkt.getSourceAddress());

            /*
             Ensure a TCP packet.
             */
            if (!(ip4Pkt.getPayload() instanceof TCP)) {
                return;
            }
            TCP tcpPkt = (TCP) ip4Pkt.getPayload();

            /*
             Ensure a Modbus/TCP request with dstPort == 502.

             This is based on the fact that an OpenPLC server uses port 502 for Modbus/TCP,
             but a Scada-LTS client uses a local port between 32768 and 60999
             (as defined by Linux kernel net.ipv4.ip_local_port_range) to connect to the OpenPLC.
             So, a Modbus/TCP packet with dstPort == 502 should be a request
             from a Scada-LTS client to an OpenPLC server.
             */
            if (tcpPkt.getDestinationPort() != 502 || !(tcpPkt.getPayload() instanceof Data)) {
                return;
            }
            Data data = (Data) tcpPkt.getPayload();

            /*
             Check if the Modbus/TCP request is tampered.

             If both srcPort and dstPort are 502, then the Modbus/TCP packet may be
             either a request from attacker or a response from OpenPLC.
             However, if we drop all traffic matching srcIP in this packet and dstTcpPort == 502,
             then this attack is blocked effectively without harm to normal OpenPLC reply to Scada-LTS.
             (a normal OpenPLC reply to Scada-LTS has dstPort between 32768 and 60999)

             Note that we can't deal with IP spoofing, so there's a risk when
             attacker uses the IP of a victim Scada-LTS to perform the attack.
             */
            if (tcpPkt.getSourcePort() == 502) {
                log.error(Ip4Address.valueOf(ip4Pkt.getSourceAddress()) +
                        " sent a malicious Modbus/TCP packet with dstTcpPort == 502 && srcTcpPort == 502");
                for (Device device : availableDevices) {
                    addGuardFlow(device.id(), Ip4Address.valueOf(ip4Pkt.getSourceAddress()));
                    log.warn("A guard flow is deployed on " + device.id());
                }
                return;
            }

            /*
             Ensure a Modbus/TCP packet with valid data.
             */
            if (data.getData().length == 0) {
                return;
            }
            String modbusData = Ethernet.bytesToHex(data.getData());

            /*
             Ensure a Modbus/TCP packet with a "write" command.

             We only inspect Modbus/TCP packet with function code "0x06" in hex,
             which is the command of "WRITE_SINGLE_REGISTER", because
             common Modbus/TCP packets with "read" commands can't do any harm to OpenPLC.
             */
            if (Integer.decode("0x" + modbusData.substring(14, 16)) != 6) {
                return;
            }

            /*
             We log the IPv4 address of all Modbus/TCP packets with "write" commands for further inspection.
             Duplicated IPv4 addresses are counted in number using a thread-safe hashmap.
             */
            Integer i = remoteHosts.containsKey(srcIP4) ?
                    remoteHosts.put(srcIP4, remoteHosts.get(srcIP4) + 1) : remoteHosts.put(srcIP4, 1);
        }
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

    /**
     * The traffic monitor measures the frequency of Modbus/TCP packets with "write" commands.
     * If frequency > threshold in a cycle, then the corresponding IPv4 address to dstTcpPort 502 is blocked.
     */
    private class ModbusTrafficMonitor implements Runnable {
        @Override
        public void run() {
            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(MONITOR_CYCLE);
                } catch (InterruptedException e) {
                    return;
                }

                /*
                 We count the number of Modbus/TCP "write" packets from each remote host,
                 so a distributed DoS attack from multiple hosts can be effectively blocked in a cycle.
                 */
                for (Map.Entry<Ip4Address, Integer> entry : remoteHosts.entrySet()) {
                    if (entry.getValue() > DOS_THRESHOLD_PER_CYCLE) {
                        log.error(entry.getKey() + " performed a " + entry.getValue() / (MONITOR_CYCLE / 1000.) +
                                " pkt/s DoS attack on Modbus/TCP.");

                        /*
                         Drop all traffic with this source IPv4 to dstTcpPort 502.
                         */
                        for (Device device : availableDevices) {
                            addGuardFlow(device.id(), entry.getKey());
                            log.warn("A guard flow is deployed on " + device.id());
                        }
                    }
                }

                /*
                 Empty remoteHosts for the next cycle.
                 */
                remoteHosts.clear();
            }
        }
    }
}
