/*
 * Copyright (C) Schweizerische Bundesbahnen SBB, 2017.
 */

package ch.sbb.matsim.mavi;

import com.jacob.activeX.ActiveXComponent;
import com.jacob.com.Dispatch;
import com.jacob.com.Variant;
import com.sun.org.apache.xalan.internal.xsltc.compiler.util.Type;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.routes.LinkNetworkRouteImpl;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.misc.Time;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.utils.objectattributes.ObjectAttributesXmlWriter;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleCapacity;
import org.matsim.vehicles.VehicleCapacityImpl;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleWriterV1;
import org.matsim.vehicles.Vehicles;
import org.matsim.vehicles.VehiclesFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/***
 *
 * @author pmanser / SBB
 *
 * IMPORTANT:
 * Download the JACOB library version 1.18 and
 * set path to the library in the VM Options (e.g. -Djava.library.path="C:\Users\u225744\Downloads\jacob-1.18\jacob-1.18")
 *
 */

public class Exporter {

    private final static String CONFIG_OUT = "output_config.xml";
    private final static String NETWORK_OUT = "transitNetwork.xml.gz";
    private final static String TRANSITSCHEDULE_OUT = "transitSchedule.xml.gz";
    private final static String TRANSITVEHICLES_OUT = "transitVehicles.xml.gz";
    private final static String ROUTEATTRIBUTES_OUT = "routeAttributes.xml.gz";
    private final static String STOPATTRIBUTES_OUT = "stopAttributes.xml.gz";

    private static Logger log;

    private ExportPTSupplyFromVisumConfigGroup exporterConfig;
    private File outputPath;
    private Config config;
    private ActiveXComponent visum;
    private Scenario scenario;
    private Network network;
    private TransitSchedule schedule;
    private Vehicles vehicles;

    private TransitScheduleFactory scheduleBuilder;
    private NetworkFactory networkBuilder;
    private VehiclesFactory vehicleBuilder;

    public static void main(String[] args) {
        new Exporter(args[0]);
    }

    public Exporter(String configFile) {
        log = Logger.getLogger(ExportPTSupplyFromVisum.class);

        this.config = ConfigUtils.loadConfig(configFile, new ExportPTSupplyFromVisumConfigGroup());
        this.exporterConfig = ConfigUtils.addOrGetModule(config, ExportPTSupplyFromVisumConfigGroup.class);

        this.outputPath = new File(this.exporterConfig.getOutputPath());
        if(!outputPath.exists())
            outputPath.mkdir();

        this.visum = new ActiveXComponent("Visum.Visum.16");
        log.info("VISUM Client gestartet.");
        try {
            run();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            visum.safeRelease();
        }
    }

    public void run() {
        loadVersion(this.visum);
        createMATSimScenario();

        if(this.exporterConfig.getPathToAttributeFile() != null)
            loadAttributes(this.visum);
        setTimeProfilFilter(this.visum);

        Dispatch net = Dispatch.get(this.visum, "Net").toDispatch();
        loadStopPoints(net);
        writeStopAttributes();
        createVehicleTypes(net);
        loadTransitLines(net);

        cleanStops();
        cleanNetwork();

        writeFiles();
    }

    private void loadVersion(ActiveXComponent visum) {
        log.info("LoadVersion started...");
        log.info("Start VISUM Client mit Version " + this.exporterConfig.getPathToVisum());
        Dispatch.call(visum, "LoadVersion", new Object[] { new Variant( this.exporterConfig.getPathToVisum() ) });
        log.info("LoadVersion finished");
    }

    private void loadAttributes(ActiveXComponent visum) {
        Dispatch io = Dispatch.get(visum, "IO").toDispatch();
        Dispatch.call(io, "LoadAttributeFile", this.exporterConfig.getPathToAttributeFile());
    }

    private void createMATSimScenario()   {
        this.scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        log.info("MATSim scenario created");
        this.scheduleBuilder = scenario.getTransitSchedule().getFactory();
        log.info("Schedule builder initialized");
        this.vehicleBuilder = scenario.getTransitVehicles().getFactory();
        log.info("Vehicle builder initialized");
        this.networkBuilder = scenario.getNetwork().getFactory();
        log.info("Network builder initialized");

        this.network = this.scenario.getNetwork();
        this.schedule = this.scenario.getTransitSchedule();
        this.vehicles = this.scenario.getTransitVehicles();
    }

    private void setTimeProfilFilter(ActiveXComponent visum)    {
        Dispatch filters = Dispatch.get(visum, "Filters").toDispatch();
        Dispatch filter = Dispatch.call(filters, "LineGroupFilter").toDispatch();
        Dispatch tpFilter = Dispatch.call(filter, "TimeProfileFilter").toDispatch();
        Dispatch.call(tpFilter, "Init");

        for(ExportPTSupplyFromVisumConfigGroup.TimeProfilFilterParams f: this.exporterConfig.getTimeProfilFilterParams().values())   {
            Dispatch.call(tpFilter, "AddCondition", f.getOp(), f.isComplement(), f.getAttribute(), f.getComparator(),
                    f.getVal(), f.getPosition());
        }

        Dispatch.put(filter, "UseFilterForTimeProfiles", true);
    }

    private void loadStopPoints(Dispatch net) {
        log.info("LoadStopPoints and CreateLoopLinks started...");
        Dispatch stopPoints = Dispatch.get(net, "StopPoints").toDispatch();
        Dispatch stopPointIterator = Dispatch.get(stopPoints, "Iterator").toDispatch();

        int nrOfStopPoints = Integer.valueOf(Dispatch.call(stopPoints, "Count").toString());
        int i = 0;

        while (i < nrOfStopPoints) {
            Dispatch item = Dispatch.get(stopPointIterator, "Item").toDispatch();

            // get the stop characteristics
            double stopPointNo = Double.valueOf(Dispatch.call(item, "AttValue", "No").toString());
            Id<TransitStopFacility> stopPointID = Id.create((int) stopPointNo, TransitStopFacility.class);
            double xCoord = Double.valueOf(Dispatch.call(item, "AttValue", "XCoord").toString());
            double yCoord = Double.valueOf(Dispatch.call(item, "AttValue", "YCoord").toString());
            Coord stopPointCoord = new Coord(xCoord, yCoord);
            String stopPointName = Dispatch.call(item, "AttValue", "Name").toString();

            // create stop node and loop link
            double fromStopIsOnNode = Double.valueOf(Dispatch.call(item, "AttValue", "IsOnNode").toString());
            double fromStopIsOnLink = Double.valueOf(Dispatch.call(item, "AttValue", "IsOnLink").toString());
            Node stopNode = null;
            if(fromStopIsOnNode == 1.0) {
                double stopNodeIDNo = Double.valueOf(Dispatch.call(item, "AttValue", "NodeNo").toString());
                Id<Node> stopNodeID = Id.createNodeId("pt_" + ((int) stopNodeIDNo));
                stopNode = this.networkBuilder.createNode(stopNodeID, stopPointCoord);
                this.network.addNode(stopNode);
            }
            else if(fromStopIsOnLink == 1.0)    {
                double stopLinkFromNodeNo = Double.valueOf(Dispatch.call(item, "AttValue", "FromNodeNo").toString());
                Id<Node> stopNodeID = Id.createNodeId("pt_" + ((int) stopLinkFromNodeNo) + "_"  + ((int) stopPointNo));
                stopNode = this.networkBuilder.createNode(stopNodeID, stopPointCoord);
                this.network.addNode(stopNode);
            }
            else    {
                log.error("something went wrong. stop must be either on node or on link.");
            }

            Id<Link> loopLinkID = Id.createLinkId("pt_" + ((int) stopPointNo));
            Link loopLink = this.networkBuilder.createLink(loopLinkID, stopNode, stopNode);
            loopLink.setLength(0.0);
            loopLink.setFreespeed(10000);
            loopLink.setCapacity(10000);
            loopLink.setNumberOfLanes(10000);
            loopLink.setAllowedModes(new HashSet<>(Arrays.asList("pt")));
            this.network.addLink(loopLink);

            // create transitStopFacility
            TransitStopFacility st = this.scheduleBuilder.createTransitStopFacility(stopPointID, stopPointCoord, false);
            st.setName(stopPointName);
            st.setLinkId(loopLinkID);

            // custom stop attributes as identifiers
            for(ExportPTSupplyFromVisumConfigGroup.StopAttributeParams params: this.exporterConfig.getStopAttributeParams().values())    {
                String name = Dispatch.call(item, "AttValue", params.getAttributeValue()).toString();
                if(!name.equals("") && !name.equals("null"))    {
                    switch ( params.getDataType() ) {
                        case Type.STRING_CLASS:
                            this.schedule.getTransitStopsAttributes().putAttribute(stopPointID.toString(), params.getAttributeName(), name);
                            break;
                        case Type.DOUBLE_CLASS:
                            this.schedule.getTransitStopsAttributes().putAttribute(stopPointID.toString(), params.getAttributeName(), Double.valueOf(name));
                            break;
                        case Type.INTEGER_CLASS:
                            double nameDouble = Double.valueOf(name);
                            this.schedule.getTransitStopsAttributes().putAttribute(stopPointID.toString(), params.getAttributeName(), (int) nameDouble);
                            break;
                        default:
                            throw new IllegalArgumentException( params.getDataType() );
                    }
                }
            }
            this.schedule.addStopFacility(st);

            i++;
            Dispatch.call(stopPointIterator, "Next");
        }

        log.info("Finished LoadStopPoints and CreateLoopLinks");
        log.info("Added " + nrOfStopPoints + " Stop Points");
    }

    private void writeStopAttributes()  {
        log.info("Writing out the stop attributes file and cleaning the scenario");
        new ObjectAttributesXmlWriter(this.schedule.getTransitStopsAttributes()).writeFile(new File(this.outputPath, STOPATTRIBUTES_OUT).getPath());

        // remove stop attributes file because of memory issues
        for(Id<TransitStopFacility> stopID: this.schedule.getFacilities().keySet())   {
            this.schedule.getTransitStopsAttributes().removeAllAttributes(stopID.toString());
        }
        log.info("Finished the scenario cleaning");
    }

    private void createVehicleTypes(Dispatch net) {
        log.info("Loading vehicle types...");
        Dispatch tSystems = Dispatch.get(net, "TSystems").toDispatch();
        Dispatch tSystemsIterator = Dispatch.get(tSystems, "Iterator").toDispatch();

        int nrOfTSystems = Integer.valueOf(Dispatch.call(tSystems, "Count").toString());
        int i = 0;

        while (i < nrOfTSystems) {
            Dispatch item = Dispatch.get(tSystemsIterator, "Item").toDispatch();

            String tSysCode = Dispatch.call(item, "AttValue", "Code").toString();
            Id<VehicleType> vehicleTypeId = Id.create(tSysCode, VehicleType.class);

            // TODO we need much more sophisticated values based on reference data
            VehicleType vehicleType = this.vehicleBuilder.createVehicleType(vehicleTypeId);
            String tSysName = Dispatch.call(item, "AttValue", "Name").toString();
            vehicleType.setDescription(tSysName);
            vehicleType.setDoorOperationMode(VehicleType.DoorOperationMode.serial);
            VehicleCapacity vehicleCapacity = new VehicleCapacityImpl();
            vehicleCapacity.setStandingRoom(500);
            vehicleCapacity.setSeats(2000);
            vehicleType.setCapacity(vehicleCapacity);

            // the following parameters do not have any influence in a deterministic simulation engine
            vehicleType.setLength(10);
            vehicleType.setWidth(2);
            vehicleType.setPcuEquivalents(1);
            vehicleType.setMaximumVelocity(10000);

            this.vehicles.addVehicleType(vehicleType);

            i++;
            Dispatch.call(tSystemsIterator, "Next");
        }
    }

    private void loadTransitLines(Dispatch net)   {
        // Fahrzeitprofile
        Dispatch timeProfiles = Dispatch.get(net, "TimeProfiles").toDispatch();
        log.info("Loading transit routes started...");
        Dispatch timeProfileIterator = Dispatch.get(timeProfiles, "Iterator").toDispatch();

        int nrOfTimeProfiles = Integer.valueOf(Dispatch.call(timeProfiles, "CountActive").toString());
        log.info("Number of active time profiles: " + nrOfTimeProfiles);
        int i = 0;

        //while (i < 5000) { // for test purposes
        while (i < nrOfTimeProfiles) {
            if(!Dispatch.call(timeProfileIterator, "Active").getBoolean())   {
                Dispatch.call(timeProfileIterator, "Next");
                continue;
            }

            log.info("Processing Time Profile " + i + " of " + nrOfTimeProfiles);
            Dispatch item = Dispatch.get(timeProfileIterator, "Item").toDispatch();

            String lineName = Dispatch.call(item, "AttValue", "LineName").toString();
            Id<TransitLine> lineID = Id.create(lineName, TransitLine.class);
            if(!this.schedule.getTransitLines().containsKey(lineID)) {
                TransitLine line = this.scheduleBuilder.createTransitLine(lineID);
                this.schedule.addTransitLine(line);
            }

            // Fahrplanfahrten
            Dispatch vehicleJourneys = Dispatch.get(item, "VehJourneys").toDispatch();
            Dispatch vehicleJourneyIterator = Dispatch.get(vehicleJourneys, "Iterator").toDispatch();

            String datenHerkunft = Dispatch.call(item, "AttValue", "LineRoute\\Line\\Datenherkunft").toString();
            String ptMode;
            if(datenHerkunft.equals("SBB_Simba.CH_2016"))
                ptMode = "Simba";
            else
                ptMode = "Hafas";

            String mode;
            if(exporterConfig.isUseDetPT())
                mode = ptMode;
            else
                mode = Dispatch.call(item, "AttValue", "TSysCode").toString();

            int nrOfVehicleJourneys = Integer.valueOf(Dispatch.call(vehicleJourneys, "Count").toString());
            int k = 0;

            while (k < nrOfVehicleJourneys) {
                Dispatch item_ = Dispatch.get(vehicleJourneyIterator, "Item").toDispatch();

                double routeName = Double.valueOf(Dispatch.call(item, "AttValue", "ID").toString());
                double from_tp_index = Double.valueOf(Dispatch.call(item_, "AttValue", "FromTProfItemIndex").toString());
                double to_tp_index = Double.valueOf(Dispatch.call(item_, "AttValue", "ToTProfItemIndex").toString());
                Id<TransitRoute> routeID = Id.create(((int) routeName) + "_" + ((int) from_tp_index)
                        + "_" + ((int) to_tp_index), TransitRoute.class);
                TransitRoute route;

                if(!this.schedule.getTransitLines().get(lineID).getRoutes().containsKey(routeID)) {
                    Dispatch lineRoute = Dispatch.get(item_, "LineRoute").toDispatch();
                    Dispatch lineRouteItems = Dispatch.get(lineRoute, "LineRouteItems").toDispatch();
                    Dispatch lineRouteItemsIterator = Dispatch.get(lineRouteItems, "Iterator").toDispatch();

                    // custom route identifiers
                    for(ExportPTSupplyFromVisumConfigGroup.RouteAttributeParams params: this.exporterConfig.getRouteAttributeParams().values())    {
                        String name = Dispatch.call(item, "AttValue", params.getAttributeValue()).toString();
                        if(!name.equals("") && !name.equals("null"))    {
                            switch ( params.getDataType() ) {
                                case Type.STRING_CLASS:
                                    this.schedule.getTransitLinesAttributes().putAttribute(routeID.toString(), params.getAttributeName(), name);
                                    break;
                                case Type.DOUBLE_CLASS:
                                    this.schedule.getTransitLinesAttributes().putAttribute(routeID.toString(), params.getAttributeName(), Double.valueOf(name));
                                    break;
                                case Type.INTEGER_CLASS:
                                    double nameDouble = Double.valueOf(name);
                                    this.schedule.getTransitLinesAttributes().putAttribute(routeID.toString(), params.getAttributeName(), (int) nameDouble);
                                    break;
                                default:
                                    throw new IllegalArgumentException( params.getDataType() );
                            }
                        }
                    }

                    // Fahrzeitprofil-Verläufe
                    List<TransitRouteStop> transitRouteStops = new ArrayList<>();
                    List<Id<Link>> routeLinks = new ArrayList<>();
                    Id<Link> startLink = null;
                    Id<Link> endLink = null;
                    TransitStopFacility fromStop = null;
                    double postlength = 0.0;
                    double delta = 0.0;

                    Dispatch fzpVerlaufe = Dispatch.get(item, "TimeProfileItems").toDispatch();
                    Dispatch fzpVerlaufIterator = Dispatch.get(fzpVerlaufe, "Iterator").toDispatch();

                    int nrFZPVerlaufe = Integer.valueOf(Dispatch.call(fzpVerlaufe, "Count").toString());
                    int l = 0;
                    boolean isFirstRouteStop = true;

                    while (l < nrFZPVerlaufe) {
                        Dispatch item__ = Dispatch.get(fzpVerlaufIterator, "Item").toDispatch();

                        double stopPointNo = Double.valueOf(Dispatch.call(item__, "AttValue", "LineRouteItem\\StopPointNo").toString());

                        double index = Double.valueOf(Dispatch.call(item__, "AttValue", "Index").toString());
                        if(from_tp_index > index || to_tp_index < index)    {
                            l++;
                            Dispatch.call(fzpVerlaufIterator, "Next");
                            continue;
                        }
                        else if(from_tp_index == index) {
                            startLink = Id.createLinkId("pt_" + ((int) stopPointNo));
                            delta = Double.valueOf(Dispatch.call(item__, "AttValue", "Dep").toString());
                        }
                        else if(to_tp_index == index) { endLink = Id.createLinkId("pt_" + ((int) stopPointNo)); }

                        Id<TransitStopFacility> stopID = Id.create((int) stopPointNo, TransitStopFacility.class);
                        TransitStopFacility stop = this.schedule.getFacilities().get(stopID);

                        double arrTime = Double.valueOf(Dispatch.call(item__, "AttValue", "Arr").toString());
                        double depTime = Double.valueOf(Dispatch.call(item__, "AttValue", "Dep").toString());
                        TransitRouteStop rst;
                        if(isFirstRouteStop) {
                            rst = this.scheduleBuilder.createTransitRouteStop(stop, Time.UNDEFINED_TIME, depTime - delta);
                            isFirstRouteStop = false;
                        }
                        else {
                            rst = this.scheduleBuilder.createTransitRouteStop(stop, arrTime - delta, depTime - delta);
                        }
                        rst.setAwaitDepartureTime(true);
                        transitRouteStops.add(rst);

                        if(fromStop != null) {
                            if(this.exporterConfig.getLinesToRoute().contains(datenHerkunft))   {
                                Dispatch lineRouteItem = Dispatch.get(lineRouteItemsIterator, "Item").toDispatch();
                                boolean startwriting = false;
                                boolean foundToStop = false;

                                while (!foundToStop) {
                                    boolean stopIsOnLink = false;

                                    double lineRouteStop = Double.MAX_VALUE;
                                    String lineRouteStopStr = Dispatch.call(lineRouteItem, "AttValue", "StopPointNo").toString();
                                    if(!lineRouteStopStr.equals("null")) {
                                        lineRouteStop = Double.valueOf(lineRouteStopStr);
                                        if(Double.valueOf(Dispatch.call(lineRouteItem, "AttValue", "StopPoint\\IsOnLink").toString()) == 1.0)
                                            stopIsOnLink = true;
                                    }

                                    boolean isToStop = String.valueOf((int) lineRouteStop).equals(stop.getId().toString());

                                    if(isToStop)   {
                                        if(stopIsOnLink) {
                                            // last link must be split into two pieces if the stop is on a link
                                            Id<TransitStopFacility> lineRouteStopId = Id.create((int) lineRouteStop, TransitStopFacility.class);
                                            Node betweenNode = this.network.getLinks().get(this.schedule.getFacilities().get(lineRouteStopId).getLinkId()).getFromNode();

                                            Id<Link> lastRouteLinkId = routeLinks.get(routeLinks.size() - 1);
                                            //this.network.removeLink(lastRouteLinkId);
                                            routeLinks.remove(routeLinks.size() - 1);
                                            String[] newLinkIdStr = lastRouteLinkId.toString().split("-");
                                            Node fromNode = this.network.getNodes().get(Id.createNodeId(newLinkIdStr[0]));

                                            Id<Link> newLinkID = Id.createLinkId(fromNode.getId().toString() + "-" + newLinkIdStr[1] + "-" + betweenNode.getId().toString());
                                            createLinkIfDoesNtExist(newLinkID, lineRouteItem, fromNode, betweenNode, false, false, ptMode);
                                            routeLinks.add(newLinkID);
                                        }
                                        routeLinks.add(stop.getLinkId());
                                        break;
                                    }

                                    boolean isFromStop = String.valueOf((int) lineRouteStop).equals(fromStop.getId().toString());
                                    if(isFromStop)
                                        startwriting = true;

                                    if(startwriting)    {
                                        double outLinkNo = Double.valueOf(Dispatch.call(lineRouteItem, "AttValue", "OutLink\\No").toString());
                                        double fromNodeNo = Double.valueOf(Dispatch.call(lineRouteItem, "AttValue", "OutLink\\FromNodeNo").toString());
                                        double toNodeNo = Double.valueOf(Dispatch.call(lineRouteItem, "AttValue", "OutLink\\ToNodeNo").toString());

                                        Id<Node> fromNodeId = Id.createNodeId("pt_" + (int) fromNodeNo);
                                        Id<Node> toNodeId = Id.createNodeId("pt_" + (int) toNodeNo);

                                        Node fromNode = createAndGetNode(fromNodeId, lineRouteItem, true);
                                        Node toNode = createAndGetNode(toNodeId, lineRouteItem, false);

                                        if(!stopIsOnLink) {
                                            Id<Link> newLinkID = Id.createLinkId(fromNode.getId().toString() + "-" + String.valueOf((int) outLinkNo) + "-" + toNode.getId().toString());
                                            createLinkIfDoesNtExist(newLinkID, lineRouteItem, fromNode, toNode, true, false, ptMode);
                                            routeLinks.add(newLinkID);
                                        }
                                        else    {
                                            Id<TransitStopFacility> lineRouteStopId = Id.create((int) lineRouteStop, TransitStopFacility.class);
                                            Node betweenNode = this.network.getLinks().get(this.schedule.getFacilities().get(lineRouteStopId).getLinkId()).getFromNode();
                                            Id<Link> newLinkID;

                                            if(!isFromStop) {
                                                newLinkID = Id.createLinkId(fromNode.getId().toString() + "-" + String.valueOf((int) outLinkNo) + "-" + betweenNode.getId().toString());
                                                createLinkIfDoesNtExist(newLinkID, lineRouteItem, fromNode, betweenNode, false, false, ptMode);
                                                routeLinks.add(newLinkID);
                                            }

                                            newLinkID = Id.createLinkId(betweenNode.getId().toString() + "-" + String.valueOf((int) outLinkNo) + "-" + toNode.getId().toString());
                                            createLinkIfDoesNtExist(newLinkID, lineRouteItem, betweenNode, toNode, false, true, ptMode);
                                            routeLinks.add(newLinkID);
                                        }
                                    }
                                    Dispatch.call(lineRouteItemsIterator, "Next");
                                    lineRouteItem = Dispatch.get(lineRouteItemsIterator, "Item").toDispatch();
                                }
                            }



                            else {
                                Node fromNode = this.network.getLinks().get(fromStop.getLinkId()).getFromNode();
                                Node toNode = this.network.getLinks().get(stop.getLinkId()).getFromNode();
                                Id<Link> newLinkID = Id.createLinkId(fromNode.getId().toString() + "-" + toNode.getId().toString());
                                if (!this.network.getLinks().containsKey(newLinkID)) {
                                    Link newLink = this.networkBuilder.createLink(newLinkID, fromNode, toNode);
                                    newLink.setLength(postlength * 1000);
                                    newLink.setFreespeed(10000);
                                    newLink.setCapacity(10000);
                                    newLink.setNumberOfLanes(10000);
                                    newLink.setAllowedModes(new HashSet<>(Arrays.asList(new String[]{"pt", ptMode})));
                                    this.network.addLink(newLink);
                                }
                                // differentiate between links with the same from- and to-node but different length
                                else {
                                    boolean hasLinkWithSameLength = false;
                                    if (this.network.getLinks().get(newLinkID).getLength() != postlength * 1000) {
                                        int m = 1;
                                        Id<Link> linkID = Id.createLinkId(fromNode.getId().toString() + "-" + toNode.getId().toString() + "." + m);
                                        while (this.network.getLinks().containsKey(linkID)) {
                                            if (this.network.getLinks().get(linkID).getLength() == postlength * 1000) {
                                                hasLinkWithSameLength = true;
                                                break;
                                            }
                                            m++;
                                            linkID = Id.createLinkId(fromNode.getId().toString() + "-" + toNode.getId().toString() + "." + m);
                                        }
                                        if (!hasLinkWithSameLength) {
                                            Link link = this.networkBuilder.createLink(linkID, fromNode, toNode);
                                            link.setLength(postlength * 1000);
                                            link.setFreespeed(10000);
                                            link.setCapacity(10000);
                                            link.setNumberOfLanes(10000);
                                            link.setAllowedModes(new HashSet<>(Arrays.asList(new String[]{"pt", ptMode})));
                                            this.network.addLink(link);
                                            newLinkID = linkID;
                                        }
                                    }
                                }
                                routeLinks.add(newLinkID);
                                routeLinks.add(stop.getLinkId());
                            }
                        }
                        postlength = Double.valueOf(Dispatch.call(item__, "AttValue", "PostLength").toString());
                        fromStop = stop;

                        l++;
                        Dispatch.call(fzpVerlaufIterator, "Next");
                    }
                    routeLinks.remove(routeLinks.size() - 1);
                    NetworkRoute netRoute = new LinkNetworkRouteImpl(startLink, endLink);
                    netRoute.setLinkIds(startLink, routeLinks, endLink);

                    route = this.scheduleBuilder.createTransitRoute(routeID, netRoute, transitRouteStops, mode);

                    this.schedule.getTransitLines().get(lineID).addRoute(route);
                }
                else    {
                    route = this.schedule.getTransitLines().get(lineID).getRoutes().get(routeID);
                }

                double depName = Double.valueOf(Dispatch.call(item_, "AttValue", "No").toString());
                Id<Departure> depID = Id.create((int) depName, Departure.class);
                double depTime = Double.valueOf(Dispatch.call(item_, "AttValue", "Dep").toString());
                Departure dep = this.scheduleBuilder.createDeparture(depID, depTime);

                Id<Vehicle> vehicleId = Id.createVehicleId(depID.toString());
                dep.setVehicleId(vehicleId);
                route.addDeparture(dep);

                String vehicleType = Dispatch.call(item, "AttValue", "TSysCode").toString();
                Id<VehicleType> vehicleTypeId = Id.create(vehicleType, VehicleType.class);
                Vehicle vehicle = this.vehicleBuilder.createVehicle(vehicleId, this.vehicles.getVehicleTypes().get(vehicleTypeId));
                this.vehicles.addVehicle(vehicle);

                k++;
                Dispatch.call(vehicleJourneyIterator, "Next");
            }
            i++;
            Dispatch.call(timeProfileIterator, "Next");
        }
        log.info("Loading transit routes finished");
    }

    private Node createAndGetNode(Id<Node> nodeID, Dispatch visumNode, boolean isFromNode) {
        Node node;
        if(this.network.getNodes().containsKey(nodeID)) {
            node = this.network.getNodes().get(nodeID);
        } else {
            double xCoord;
            double yCoord;
            if(isFromNode) {
                xCoord = Double.valueOf(Dispatch.call(visumNode, "AttValue", "OutLink\\FromNode\\XCoord").toString());
                yCoord = Double.valueOf(Dispatch.call(visumNode, "AttValue", "OutLink\\FromNode\\YCoord").toString());
            }
            else {
                xCoord = Double.valueOf(Dispatch.call(visumNode, "AttValue", "OutLink\\ToNode\\XCoord").toString());
                yCoord = Double.valueOf(Dispatch.call(visumNode, "AttValue", "OutLink\\ToNode\\YCoord").toString());
            }
            Node no = this.networkBuilder.createNode(nodeID, new Coord(xCoord, yCoord));
            this.network.addNode(no);
            node = no;
        }
        return node;
    }

    private void createLinkIfDoesNtExist(Id<Link> linkID, Dispatch visumLink, Node fromNode, Node toNode, boolean isOnNode, boolean fromNodeIsBetweenNode, String ptMode)    {
        if (!this.network.getLinks().containsKey(linkID)) {
            Link link = this.networkBuilder.createLink(linkID, fromNode, toNode);
            String lengthStr = Dispatch.call(visumLink, "AttValue", "OutLink\\Length").toString();
            double length;
            if(!lengthStr.equals("null"))
                length = Double.valueOf(lengthStr);
            else
                length = Double.valueOf(Dispatch.call(visumLink, "AttValue", "InLink\\Length").toString());
            if(!isOnNode) {
                double fraction = Double.valueOf(Dispatch.call(visumLink, "AttValue", "StopPoint\\RelPos").toString());

                double fromNodeNo = Double.valueOf(fromNode.getId().toString().split("_")[1]);
                double toNodeNo = Double.valueOf(toNode.getId().toString().split("_")[1]);

                double fromNodeStopLink = Double.valueOf(Dispatch.call(visumLink, "AttValue", "StopPoint\\FromNodeNo").toString());
                if(fromNodeNo == fromNodeStopLink && !fromNodeIsBetweenNode)   {
                    length = length * fraction;
                    log.info("I am here");
                }
                else if(toNodeNo == fromNodeNo && fromNodeIsBetweenNode)     {
                    length = length * fraction;
                    log.info("I am here2");
                }
                else {
                    length = length * (1 - fraction);
                    log.info("... and there");
                }
            }
            link.setLength(length * 1000);
            link.setFreespeed(10000);
            link.setCapacity(10000);
            link.setNumberOfLanes(10000);
            link.setAllowedModes(new HashSet<>(Arrays.asList(new String[]{"pt", ptMode})));
            this.network.addLink(link);
        }
    }

    private void cleanStops()   {
        Set<Id<TransitStopFacility>> stopsToKeep = new HashSet<>();
        Set<Id<TransitStopFacility>> stopsToRemove = new HashSet<>();

        for(TransitLine line: this.schedule.getTransitLines().values())   {
            for(TransitRoute route: line.getRoutes().values())  {
                for(TransitRouteStop stops: route.getStops()) {
                    stopsToKeep.add(stops.getStopFacility().getId());
                }
            }
        }
        for(Id<TransitStopFacility> stopId: this.schedule.getFacilities().keySet()) {
            if(!stopsToKeep.contains(stopId))
                stopsToRemove.add(stopId);
        }
        log.info("Cleared " + stopsToRemove.size() + " unused stop facilities.");
        for(Id<TransitStopFacility> stopId: stopsToRemove)
            this.schedule.removeStopFacility(this.schedule.getFacilities().get(stopId));
        stopsToKeep.clear();
        stopsToRemove.clear();
    }

    private void cleanNetwork() {
        Set<Id<Link>> linksToKeep = new HashSet<>();
        Set<Id<Link>> linksToRemove = new HashSet<>();

        for(TransitLine line: this.schedule.getTransitLines().values())   {
            for(TransitRoute route: line.getRoutes().values())  {
                linksToKeep.add(route.getRoute().getStartLinkId());
                linksToKeep.add(route.getRoute().getEndLinkId());
                for(Id<Link> linkId: route.getRoute().getLinkIds()) {
                    linksToKeep.add(linkId);
                }
            }
        }
        for(Id<Link> linkId: this.network.getLinks().keySet()) {
            if(!linksToKeep.contains(linkId))
                linksToRemove.add(linkId);
        }
        log.info("Cleared " + linksToRemove.size() + " unused links.");
        for(Id<Link> linkId: linksToRemove)
            this.network.removeLink(linkId);
        linksToKeep.clear();
        linksToRemove.clear();

        Set<Id<Node>> nodesToRemove = new HashSet<>();

        for(Node node: this.network.getNodes().values())  {
            if(node.getInLinks().size() == 0 && node.getOutLinks().size() == 0)
                nodesToRemove.add(node.getId());
        }
        log.info("Cleared " + nodesToRemove.size() + " unused nodes.");
        for(Id<Node> nodeId: nodesToRemove)
            this.network.removeNode(nodeId);
    }

    private void writeFiles()   {
        new NetworkWriter(this.network).write(new File(this.outputPath, NETWORK_OUT).getPath());
        new TransitScheduleWriter(this.schedule).writeFile(new File(this.outputPath, TRANSITSCHEDULE_OUT).getPath());
        new VehicleWriterV1(this.vehicles).writeFile(new File(this.outputPath, TRANSITVEHICLES_OUT).getPath());
        new ObjectAttributesXmlWriter(this.schedule.getTransitLinesAttributes()).writeFile(new File(this.outputPath, ROUTEATTRIBUTES_OUT).getPath());

        new ConfigWriter(this.config).writeFileV2(new File(this.outputPath, CONFIG_OUT).getPath());
    }
}