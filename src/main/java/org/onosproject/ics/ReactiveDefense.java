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
import org.onlab.packet.Ethernet;
import org.onlab.packet.ICMP;
import org.onlab.packet.ICMPEcho;
import org.onlab.packet.IPacket;
import org.onlab.packet.TCP;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
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
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
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
    private ReactivePacketProcessor processor;
    private InnerDeviceListener deviceListener;
    private ApplicationId appId;
    private Iterable<Device> availableDevices;

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

        deviceListener = new InnerDeviceListener();
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
    }

    @Deactivate
    protected void deactivate() {

        cfgService.unregisterProperties(getClass(), false);

        flowRuleService.removeFlowRulesById(appId);
        packetService.removeProcessor(processor);
        processor = null;
        deviceService.removeListener(deviceListener);
        deviceListener = null;

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

            // port 1 == eth1, port 2 == eth2 by default, if bridges and ports are not modified
            for (int i = 1; i <= 2; ++i) {
                // in_port=1/2
                TrafficSelector initSelector = DefaultTrafficSelector.builder()
                        .matchInPort(PortNumber.portNumber(i))
                        .build();
                // actions=output:1/2,controller
                TrafficTreatment initTreatment = DefaultTrafficTreatment.builder()
                        .setOutput(PortNumber.portNumber(3 - i))
                        .setOutput(PortNumber.CONTROLLER)
                        .build();

                ForwardingObjective initObjective = DefaultForwardingObjective.builder()
                        .withSelector(initSelector)
                        .withTreatment(initTreatment)
                        .fromApp(appId)
                        .withPriority(10)
                        .makePermanent()
                        .withFlag(ForwardingObjective.Flag.VERSATILE)
                        .add();

                flowObjectiveService.forward(device.id(), initObjective);
                log.debug("Installed flow: " + initObjective);
            }
        }
    }

    /**
     * Packet processor responsible for forwarding packets along their paths.
     */
    private class ReactivePacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {

            if (context.isHandled()) {
                log.error("onos-app-ics is incompatible with onos-app-fwd");
                return;
            }

            Ethernet ethPkt = context.inPacket().parsed();
            if (ethPkt == null) {
                log.error("Corrupted ethernet packet");
                return;
            }

            // Inspects only IPv4 unicast packets
            if (ethPkt.getEtherType() == Ethernet.TYPE_IPV4 && !ethPkt.getDestinationMAC().isMulticast()) {
                /*
                 IPv4{version=4, headerLength=5, diffServ=0, totalLength=60, identification=-22319,
                 flags=2, fragmentOffset=0, ttl=61, protocol=6, checksum=-5073,
                 sourceAddress=-1408224767, destinationAddress=-1062729215, options=null, isTruncated=false}
                */
                IPacket ipPkt = ethPkt.getPayload();

                /*
                 TCP{sourcePort=49334, destinationPort=502, sequence=-1638985037, acknowledge=-244341676,
                 dataOffset=8, flags=16, windowSize=126, checksum=-6849, urgentPointer=0,
                 options=[1, 1, 8, 10, 93, -26, -38, 27, -50, 60, -40, -111]}

                 ICMP{icmpType=8, icmpCode=0, checksum=28508}
                */
                IPacket tcpPkt = ipPkt.getPayload();
                if (tcpPkt == null) {
                    return;
                } else if (tcpPkt instanceof TCP) {
                    log.info("TCP packet: " + tcpPkt);
                } else if (tcpPkt instanceof ICMP) {
                    log.info("ICMP packet: " + tcpPkt);
                } else {
                    log.error("unknown 'TCP' packet: " + tcpPkt);
                }

                /*
                 ICMPEcho{ICMP echo identifier=-26674, ICMP echo sequenceNumber=0}

                 Data{data=[0, 0, 0, 0, 0, 6, 1, 1, 0, 0, 0, 1]}
                 Data{data=[]}
                 */
                IPacket modbusPkt = tcpPkt.getPayload();
                if (modbusPkt == null) {
                    return;
                } else if (modbusPkt instanceof ICMPEcho) {
                    log.info("ICMPEcho packet: " + modbusPkt);
                } else if (modbusPkt instanceof Data) {
                    log.info("Modbus packet: " + modbusPkt);
                } else {
                    log.error("unknown 'modbus' packet: " + modbusPkt);
                }

                /*
                 Data{data=[-99, 64, 83, -108, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]}
                 */
            }
        }
    }

    /**
     * Listens for device availability to initialize flows.
     */
    private class InnerDeviceListener implements DeviceListener {
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
                        for (Device device : currentAvailableDevices) {
                            log.info("New available device: " + device);
                        }
                        initFlows(currentAvailableDevices);
                    }
                    break;
                default:
                    break;
            }
        }
    }
}
