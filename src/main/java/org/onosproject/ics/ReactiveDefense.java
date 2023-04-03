/*
 * Copyright 2023-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.ics;

import org.onlab.packet.Data;
import org.onlab.packet.EthType;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TCP;
import org.onlab.packet.TpPort;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.cli.net.IpProtocol;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.Device;
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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * An ONOS App for industrial control system.
 */
@Component(immediate = true,
        service = {ReactiveDefenseService.class},
        property = {
                "someProperty=Some Default String Value"
        })
public class ReactiveDefense implements ReactiveDefenseService {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private String someProperty;
    private List<Ethernet> ethList;
    private ReactivePacketProcessor processor;
    private OvSDeviceListener deviceListener;
    private ApplicationId appId;
    private Iterable<Device> availableDevices;
    private Thread t;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Activate
    protected void activate() {

        cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("org.onosproject.ics");

        ethList = Collections.synchronizedList(new ArrayList<>());
        deviceListener = new OvSDeviceListener();
        deviceService.addListener(deviceListener);
        processor = new ReactivePacketProcessor();
        packetService.addProcessor(processor, PacketProcessor.director(3));

        availableDevices = deviceService.getAvailableDevices(Device.Type.SWITCH);
        initFlows(availableDevices);

        log.info(" ___    ____   ____      ____  _____ _____ _____ _   _ ____  _____");
        log.info("|_ _|  / ___| / ___|    |  _ \\| ____|  ___| ____| \\ | / ___|| ____|");
        log.info(" | |  | |     \\___ \\    | | | |  _| | |_  |  _| |  \\| \\___ \\|  _|");
        log.info(" | |  | |___   ___) |   | |_| | |___|  _| | |___| |\\  |___) | |___");
        log.info("|___|  \\____| |____/    |____/|_____|_|   |_____|_| \\_|____/|_____|");
        log.info("Started appId=" + appId.id());

        // Starts modbus traffic monitor
        t = new Thread(new ModbusTrafficMonitor());
        t.start();
    }

    @Deactivate
    protected void deactivate() {

        cfgService.unregisterProperties(getClass(), false);

        t.interrupt();
        flowRuleService.removeFlowRulesById(appId);
        packetService.removeProcessor(processor);
        processor = null;
        deviceService.removeListener(deviceListener);
        deviceListener = null;
        availableDevices = null;
        ethList = null;

        log.info(" ___    ____   ____      ____  _____ _____ _____ _   _ ____  _____");
        log.info("|_ _|  / ___| / ___|    |  _ \\| ____|  ___| ____| \\ | / ___|| ____|");
        log.info(" | |  | |     \\___ \\    | | | |  _| | |_  |  _| |  \\| \\___ \\|  _|");
        log.info(" | |  | |___   ___) |   | |_| | |___|  _| | |___| |\\  |___) | |___");
        log.info("|___|  \\____| |____/    |____/|_____|_|   |_____|_| \\_|____/|_____|");
        log.info("Stopped appId=" + appId.id());
        appId = null;
    }

    @Modified
    public void modified(ComponentContext context) {

        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = Tools.get(properties, "someProperty");
        }
        log.info("Reconfigured appId=" + appId.id());
    }

    @Override
    public void initFlows(Iterable<Device> newAvailableDevices) {

        for (Device device : newAvailableDevices) {
            flowRuleService.purgeFlowRules(device.id());

            /*
             allow all traffic by default, with priority 10
             port 1 == eth1, port 2 == eth2 by default, if bridges and ports are not modified
            */
            flowObjectiveService.forward(device.id(), DefaultForwardingObjective.builder()
                    .fromApp(appId).makePermanent().withFlag(ForwardingObjective.Flag.SPECIFIC)
                    .withPriority(10)
                    .withSelector(DefaultTrafficSelector.builder()
                            .matchInPort(PortNumber.portNumber(1))
                            .build())
                    .withTreatment(DefaultTrafficTreatment.builder()
                            .setOutput(PortNumber.portNumber(2))
                            .setOutput(PortNumber.CONTROLLER)
                            .build())
                    .add()
            );
            flowObjectiveService.forward(device.id(), DefaultForwardingObjective.builder()
                    .fromApp(appId).makePermanent().withFlag(ForwardingObjective.Flag.SPECIFIC)
                    .withPriority(10)
                    .withSelector(DefaultTrafficSelector.builder()
                            .matchInPort(PortNumber.portNumber(2))
                            .build())
                    .withTreatment(DefaultTrafficTreatment.builder()
                            .setOutput(PortNumber.portNumber(1))
                            .setOutput(PortNumber.CONTROLLER)
                            .build())
                    .add()
            );

            /* Start Traditional Countermeasure

            Compared with adaptive countermeasure, the traditional countermeasure can't defense attacks
            made by an attacker within the same subnet 172.16.50.1/24 of Scada-LTS.

            // By default, drop all requests to Modbus server.
            // Since OpenPLC server won't send message deliberately, we don't need to consider tcpSrcPort==502.
            // Drops all Modbus/TCP traffic whose dstTcpPort==502, with priority 50.
            flowObjectiveService.forward(device.id(), DefaultForwardingObjective.builder()
                    .fromApp(appId).makePermanent().withFlag(ForwardingObjective.Flag.SPECIFIC)
                    .withPriority(50)
                    .withSelector(DefaultTrafficSelector.builder()
                            .matchEthType(EthType.EtherType.IPV4.ethType().toShort())
                            .matchIPProtocol((byte) IpProtocol.TCP.value())
                            .matchTcpDst(TpPort.tpPort(502))
                            .build())
                    .withTreatment(DefaultTrafficTreatment.builder()
                            .drop() // drop is incompatible with all other actions
                            .build())
                    .add()
            );

            // Allows all modbus/TCP traffic whose IP is in trusted subnets, with priority 100.
            // From ScadaLTS 172.16.50.0/24 to OpenPLC 192.168.0.0/16 whose tcpDstPort==502.
            // (tcpSrcPort==502 is not blocked)
            flowObjectiveService.forward(device.id(), DefaultForwardingObjective.builder()
                    .fromApp(appId).makePermanent().withFlag(ForwardingObjective.Flag.SPECIFIC)
                    .withPriority(100)
                    .withSelector(DefaultTrafficSelector.builder()
                            .matchInPort(PortNumber.portNumber(2))
                            .matchEthType(EthType.EtherType.IPV4.ethType().toShort())
                            .matchIPSrc(Ip4Prefix.valueOf("172.16.50.0/24"))
                            .matchIPDst(Ip4Prefix.valueOf("192.168.0.0/16"))
                            .matchIPProtocol((byte) IpProtocol.TCP.value())
                            .matchTcpDst(TpPort.tpPort(502))
                            .build())
                    .withTreatment(DefaultTrafficTreatment.builder()
                            .setOutput(PortNumber.portNumber(1))
                            .setOutput(PortNumber.CONTROLLER)
                            .build())
                    .add()
            );

            End Traditional Countermeasure */

            log.info("Initialized flows on switch: " + device.id());
        }
    }

    /**
     * Packet processor responsible for forwarding packets along their paths.
     */
    private class ReactivePacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {

            if (context.isHandled()) {
                log.error("ICS DEFENSE is incompatible with onos-app-fwd");
                return;
            }

            if (context.inPacket().parsed() == null) {
                return;
            }
            Ethernet ethPkt = context.inPacket().parsed();

            /*
             Inspects only IPv4 unicast Modbus/TCP packets

             IPv4{version=4, headerLength=5, diffServ=0, totalLength=60, identification=-22319,
             flags=2, fragmentOffset=0, ttl=61, protocol=6, checksum=-5073,
             sourceAddress=-1408224767, destinationAddress=-1062729215, options=null, isTruncated=false}
            */
            if (ethPkt.getEtherType() != Ethernet.TYPE_IPV4 || ethPkt.getDestinationMAC().isMulticast()) {
                return;
            }
            IPv4 ipv4Pkt = (IPv4) ethPkt.getPayload();

            /*
             TCP{sourcePort=49334, destinationPort=502, sequence=-1638985037, acknowledge=-244341676,
             dataOffset=8, flags=16, windowSize=126, checksum=-6849, urgentPointer=0,
             options=[1, 1, 8, 10, 93, -26, -38, 27, -50, 60, -40, -111]}

             ICMP{icmpType=8, icmpCode=0, checksum=28508}
            */
            if (!(ipv4Pkt.getPayload() instanceof TCP)) {
                return;
            }
            TCP tcpPkt = (TCP) ipv4Pkt.getPayload();
            if (tcpPkt.getDestinationPort() != 502 && tcpPkt.getSourcePort() != 502) {
                return;
            }

            /*
             Data{data=[0, 0, 0, 0, 0, 6, 1, 1, 0, 0, 0, 1]}
             Data{data=[]}

             ICMPEcho{ICMP echo identifier=-26674, ICMP echo sequenceNumber=0}
             */
            if (!(tcpPkt.getPayload() instanceof Data)) {
                return;
            }
            Data modbusPkt = (Data) tcpPkt.getPayload();

            if (modbusPkt.getData().length == 0) {
                return;
            }
            log.debug("Modbus packet: " + modbusPkt);

            /*
             We consider Modbus write packets sent to OpenPLC, OpenPLC responses are ignored.
             We analyze frequency of Modbus write packets sent to OpenPLC,
             if frequency > 10pkt/s, then we think this is a DoS attack on manipulating the OpenPLC.
            */
            Modbus modbus = new Modbus(modbusPkt.getData());
            if (tcpPkt.getDestinationPort() != 502 ||
                    modbus.functionCode != ModbusFunctionCode.WRITE_SINGLE_REGISTER) {
                return;
            }
            log.debug("Modbus write command: " + modbus);

            /*
             Stores ethPkt for frequency analysis.
             ethList is emptied every 1s.
            */
            ethList.add(ethPkt);
        }
    }

    /**
     * Listens for device availability to initialize flows.
     */
    private class OvSDeviceListener implements DeviceListener {
        @Override
        public void event(DeviceEvent event) {
            switch (event.type()) {
                case DEVICE_AVAILABILITY_CHANGED: case DEVICE_ADDED:
                    log.debug(event.type().name());

                    Collection<Device> currentAvailableDevices = (Collection<Device>) deviceService
                            .getAvailableDevices(Device.Type.SWITCH);
                    currentAvailableDevices.removeAll((Collection<Device>) availableDevices);
                    availableDevices = deviceService.getAvailableDevices(Device.Type.SWITCH);

                    if (!currentAvailableDevices.isEmpty()) {
                        initFlows(currentAvailableDevices);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Parse and interpret Modbus data.
     * We focus on Modbus write data, in other words,
     * Modbus packet with FunctionCode == WRITE_SINGLE_REGISTER
     */
    private class Modbus implements Serializable {
        private ModbusFunctionCode functionCode = ModbusFunctionCode.UNKNOWN;
        private ModbusReferenceNumber referenceNumber = ModbusReferenceNumber.UNKNOWN;
        private ModbusData data = ModbusData.UNKNOWN;

        /**
         * Default constructor.
         */
        public Modbus() {
            super();
        }

        /**
         * Constructor to interpret Modbus packet.
         * @param data Modbus packet in byte[]
         */
        public Modbus(byte[] data) {
            switch (data[7]) {
                case 1:
                    functionCode = ModbusFunctionCode.READ_COILS;
                    break;
                case 3:
                    functionCode = ModbusFunctionCode.READ_HOLDING_REGISTERS;
                    break;
                case 6:
                    functionCode = ModbusFunctionCode.WRITE_SINGLE_REGISTER;
                    switch (data[9]) {
                        case 2:
                            referenceNumber = ModbusReferenceNumber.SET_POINT;
                            this.data = ModbusData.VALUE;
                            break;
                        case 4:
                            referenceNumber = ModbusReferenceNumber.MODE;
                            switch (data[11]) {
                                case 0:
                                    this.data = ModbusData.AUTO;
                                    break;
                                case 1:
                                    this.data = ModbusData.MANUAL;
                                    break;
                                default:
                                    break;
                            }
                        case 5:
                            referenceNumber = ModbusReferenceNumber.CONTROL;
                            switch (data[11]) {
                                case 0:
                                    this.data = ModbusData.HEATER_OFF;
                                    break;
                                case 1:
                                    this.data = ModbusData.HEATER_ON;
                                    break;
                                default:
                                    break;
                            }
                        default:
                            break;
                    }
                default:
                    break;
            }
        }

        @Override
        public String toString() {
            return functionCode + " " + referenceNumber + " " + this.data;
        }
    }

    /**
     * Monitor Modbus write packets traffic.
     * If Modbus write packets frequency >= 10pkt/s, then
     * deploy flows to drop Modbus write packets from the highest occurrence srcMACAddr.
     */
    private class ModbusTrafficMonitor implements Runnable {
        @Override
        public void run() {
            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }

                if (ethList.size() >= 10) {
                    log.error("Modbus DoS detected with a frequency of " + ethList.size() + "pkt/s");

                    /*
                     Use HashMap to get the highest occurrence srcMACAddress in all DoS packets,
                     usually it is the attacker's MAC address.
                     */
                    HashMap<MacAddress, Integer> map = new HashMap<>();
                    for (Ethernet ethPkt : ethList) {
                        if (map.containsKey(ethPkt.getSourceMAC())) {
                            int count = map.get(ethPkt.getSourceMAC());
                            map.put(ethPkt.getSourceMAC(), count + 1);
                        } else {
                            map.put(ethPkt.getSourceMAC(), 1);
                        }
                    }

                    int maxCount = Collections.max(map.values());
                    MacAddress attacker = MacAddress.NONE;
                    for (Map.Entry<MacAddress, Integer> entry : map.entrySet()) {
                        if (entry.getValue() == maxCount) {
                            attacker = entry.getKey();
                        }
                    }

                    /*
                     Temporarily blocks Dos Modbus packets.
                     The SrcMAC approach is more useful than SrcIP
                     */
                    for (Device device : availableDevices) {
                        flowObjectiveService.forward(device.id(), DefaultForwardingObjective.builder()
                                .fromApp(appId).makeTemporary(10).withFlag(ForwardingObjective.Flag.SPECIFIC)
                                .withPriority(10000)
                                .withSelector(DefaultTrafficSelector.builder()
                                        .matchEthSrc(attacker)
                                        .matchEthType(EthType.EtherType.IPV4.ethType().toShort())
                                        .matchIPDst(Ip4Prefix.valueOf("192.168.0.0/16"))
                                        .matchIPProtocol((byte) IpProtocol.TCP.value())
                                        .matchTcpDst(TpPort.tpPort(502))
                                        .build())
                                .withTreatment(DefaultTrafficTreatment.builder()
                                        .drop()
                                        .build())
                                .add()
                        );
                        log.info("Defense flow deployed on " + device.id());
                    }
                }

                ethList.clear();
            }
        }
    }
}
