package org.onosproject.ics;

import org.onlab.packet.Ip4Address;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;

import java.util.Collection;

/**
 * The interface provides definitions of methods for the app component to implement.
 */
public interface ReactiveDefenceService {

    /**
     * Initialize normal flows in OVSes.
     * If you have specified OVSes with specified flows, then
     * criteria should be deployed here to distinguish them from others.
     *
     * @param newAvailableDevices New available devices
     */
    void initFlows(Collection<Device> newAvailableDevices);

    /**
     * A utility method to add a flow entry on given OVS to accept all traffic
     * from inPort to outPort and mirror the traffic to ONOS SDN controller.
     *
     * @param deviceId device ID
     * @param inPort input port
     * @param outPort output port
     */
    void addNormalFlow(DeviceId deviceId, int inPort, int outPort);

    /**
     * A utility method to add a flow entry on given OVS
     * dropping all traffic with the given srcIP to dstTcpPort 502.
     *
     * @param deviceId device ID
     * @param ip4 src IPv4 address
     */
    void addGuardFlow(DeviceId deviceId, Ip4Address ip4);
}
