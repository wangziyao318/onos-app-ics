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

import org.onlab.packet.Ethernet;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.Device;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
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
import org.onosproject.net.host.HostService;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.topology.TopologyService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Properties;

import static org.onlab.util.Tools.get;

/**
 * An ONOS App for industrial control system.
 */
@Component(immediate = true,
        service = {SomeInterface.class},
        property = {
                "someProperty=Some Default String Value",
        })
public class ReactiveDefense implements SomeInterface {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private String someProperty;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    private ReactivePacketProcessor processor;
    private InnerDeviceListener deviceListener;
    private ApplicationId appId;

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());

        // register always produces the same appId
        appId = coreService.registerApplication("org.onosproject.ics");

        deviceListener = new InnerDeviceListener();
        deviceService.addListener(deviceListener);
        processor = new ReactivePacketProcessor();
        packetService.addProcessor(processor, PacketProcessor.director(2));

        /* initialize flows: all traffic sent to controller:65535 */
        Iterable<Device> devices = deviceService.getDevices();
        Iterable<Device> availableDevices = deviceService.getAvailableDevices();

        for (Device device : devices) {
            log.info("device-id: " + device.id());
        }

        for (Device availableDevice : availableDevices) {
            log.error("available-device-id: " + availableDevice.id());
            // purge all old flow rules in OVSes
            flowRuleService.purgeFlowRules(availableDevice.id());
        }

        // we can only use logic port 1,2, but we can't always ensure port1==eth1, port2==eth2.
        // So, don't change port config, just remain all bridge and all ports intact
        for (int i = 1; i <= 2; ++i) {
            TrafficSelector objectiveSelector = DefaultTrafficSelector.builder()
                    .matchInPort(PortNumber.portNumber(i))
                    .build();
            // not only output eth1/2, but also sent to controller
            TrafficTreatment defaultTreatment = DefaultTrafficTreatment.builder()
                    .setOutput(PortNumber.portNumber(3 - i))
                    .setOutput(PortNumber.CONTROLLER)
                    .build();

            ForwardingObjective objective = DefaultForwardingObjective.builder()
                    .withSelector(objectiveSelector)
                    .withTreatment(defaultTreatment)
                    .fromApp(appId)
                    .withPriority(10)
                    .makeTemporary(10)
                    .withFlag(ForwardingObjective.Flag.VERSATILE)
                    .add();

            for (Device availableDevice : availableDevices) {
                flowObjectiveService.forward(availableDevice.id(), objective);
            }
        }

        Iterable<Host> hosts = hostService.getHosts();
        for (Host host : hosts) {
            log.error("known host: " + host);
        }


        log.info("===== Started id=" + appId.id() + " =====");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);

        flowRuleService.removeFlowRulesById(appId);
        deviceService.removeListener(deviceListener);
        packetService.removeProcessor(processor);
        deviceListener = null;
        processor = null;
        appId = null;
        log.info("===== Stopped =====");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = get(properties, "someProperty");
        }
        log.info("===== Reconfigured =====");
    }

    @Override
    public void someMethod() {
        log.info("Invoked");
    }

    /**
     * Packet processor responsible for forwarding packets along their paths.
     */
    private class ReactivePacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                log.error("pkt has been handled: " + context.inPacket().parsed());
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            if (ethPkt == null) {
                log.error("not an ethernet pkt !!!!! Maybe corrupted");
                return;
            }

            if (ethPkt.getEtherType() == Ethernet.TYPE_LLDP || ethPkt.getEtherType() == Ethernet.TYPE_BSN) {
                log.error("bail all control packets");
                return;
            }

            HostId dstId = HostId.hostId(ethPkt.getDestinationMAC());

            if (ethPkt.getEtherType() == Ethernet.TYPE_IPV4 && dstId.mac().isMulticast()) {
                log.error("not process IPv4 multicast pkt");
                return;
            }

            Host dst = hostService.getHost(dstId);
            // pkt dst host not known, for example: ping 10.0.0.4 which is invalid ip addr
            if (dst == null) {
                flood(context);
                return;
            }

            log.error("we know the pkt dst host, and we can now deploy flows to forward it. " +
                    "The pkt payload is: " + ethPkt.getPayload());
        }
    }

    /**
     * Listen all device event.
     */
    private class InnerDeviceListener implements DeviceListener {
        @Override
        public void event(DeviceEvent event) {
            switch (event.type()) {
                case DEVICE_AVAILABILITY_CHANGED:
                    log.error("device availability changed");
                    break;
                case DEVICE_ADDED:
                    log.error("new device added");
                    break;
                case DEVICE_REMOVED:
                    log.error("device removed");
                    break;
                case PORT_ADDED:
                    log.error("port added");
                    break;
                case PORT_REMOVED:
                    log.error("port removed");
                    break;
                case PORT_UPDATED:
                    log.error("port updated");
                    break;
                case DEVICE_UPDATED:
                    log.error("device updated");
                    break;
                case DEVICE_SUSPENDED:
                    log.error("device suspended");
                    break;
                default: // PORT_STATS_UPDATED: very commonly seen
                    break;
            }
        }
    }

    /**
     * Floods the specified packet if permissible.
     * @param context packet
     */
    private void flood(PacketContext context) {
        if (topologyService.isBroadcastPoint(topologyService.currentTopology(), context.inPacket().receivedFrom())) {
            log.error("flood pkt sent: " + context.inPacket().parsed());
            packetOut(context, PortNumber.FLOOD);
        } else {
            log.error("flood pkt blocked: " + context.inPacket().parsed());
            context.block();
        }
    }

    /**
     * Sends a packet out the specified port.
     * @param context packet
     * @param portNumber output port
     */
    private void packetOut(PacketContext context, PortNumber portNumber) {
        context.treatmentBuilder().setOutput(portNumber);
        context.send();
    }
}
