# onos-app-ics

This is an ONOS app component designed to not only provide normal operation of [the ICS network](https://github.com/sfl0r3nz05/OT-NWbasedOnGNS3/blob/master/network/ics/README.md#ics-network) but also defend both [injection attack on Modbus/TCP](https://github.com/sfl0r3nz05/OT-NWbasedOnGNS3/blob/master/modbus/attack/README.md) and [Slowloris on HTTP](https://github.com/sfl0r3nz05/OT-NWbasedOnGNS3/blob/master/http/attack/README.md). This one is not compatible with "An ICS Forwarding" and they can't be both activated.

## Usage

1. Clone the repo with IntelliJ IDEA, and then select OpenJDK11 as the SDK from "Project Structure".
2. Use the "Reload" button in Maven sidebar to download all required Maven packages.
3. Run `mvn clean` and `mvn install` using the "clean" and "install" button in Maven lifecycle to generate the app component as a ".oar" archive file in "./target" folder. You can also download the ".oar" file [here](https://github.com/wangziyao318/onos-app-ics/releases/tag/v1.0).
4. Upload that ".oar" file onto ONOS web UI following [this guide](https://github.com/sfl0r3nz05/OT-NWbasedOnGNS3/blob/master/network/ics/nodes/ONOS.md).
5. Activate the "An ICS Guard" app in ONOS web UI following [this guide](https://github.com/sfl0r3nz05/OT-NWbasedOnGNS3/blob/master/network/ics/nodes/ONOS.md).

![image](https://github.com/wangziyao318/onos-app-ics/assets/69375071/c6b7715a-ee26-4790-9507-fee19b303465)
