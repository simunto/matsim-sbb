package ch.sbb.matsim.rerouting2;

import ch.sbb.matsim.analysis.tripsandlegsanalysis.RailTripsAnalyzer;
import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.config.SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet;
import ch.sbb.matsim.routing.pt.raptor.AccessEgressRouteCache;
import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorParametersForPerson;
import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorTransferCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.LeastCostRaptorRouteSelector;
import ch.sbb.matsim.routing.pt.raptor.RaptorInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.RaptorIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.RaptorParametersForPerson;
import ch.sbb.matsim.routing.pt.raptor.RaptorRoute;
import ch.sbb.matsim.routing.pt.raptor.RaptorRoute.RoutePart;
import ch.sbb.matsim.routing.pt.raptor.RaptorRouteSelector;
import ch.sbb.matsim.routing.pt.raptor.RaptorStaticConfig;
import ch.sbb.matsim.routing.pt.raptor.RaptorStaticConfig.RaptorOptimization;
import ch.sbb.matsim.routing.pt.raptor.RaptorTransferCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.RaptorUtils;
import ch.sbb.matsim.routing.pt.raptor.SBBIntermodalRaptorStopFinder;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptor;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorCore.TravelInfo;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorData;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup;
import org.matsim.core.config.groups.PlanCalcScoreConfigGroup.ModeParams;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.router.DefaultRoutingRequest;
import org.matsim.core.router.RoutingRequest;
import org.matsim.core.router.SingleModeNetworksCache;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacilitiesFactory;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.Facility;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

public class MatrixRouterParts {

    final static String TRY = "Tree";
    final static String columNames = "Z:/99_Playgrounds/MD/Umlegung2/Visum/ZoneToNode.csv";
    final static String demand = "Z:/99_Playgrounds/MD/Umlegung2/visum/Demand2018.omx";
    final static String saveFileInpout = "Z:/99_Playgrounds/MD/Umlegung2/2018/saveFile.csv";
    final static String schedualFile = "Z:/99_Playgrounds/MD/Umlegung2/2018/transitSchedule.xml.gz";
    final static String netwoekFile = "Z:/99_Playgrounds/MD/Umlegung2/2018/transitNetwork.xml.gz";
    final static String output = "Z:/99_Playgrounds/MD/Umlegung2/routes" + TRY + "_Bern_STGallen.csv";
    final InputDemand inputDemand;
    final Map<Id<Link>, DemandStorage2> idDemandStorageMap = createLinkDemandStorage();
    final ActivityFacilitiesFactory afFactory = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getActivityFacilities().getFactory();
    final Config config;
    final Scenario scenario;
    final SwissRailRaptor swissRailRaptor;
    final RailTripsAnalyzer railTripsAnalyzer;
    final SwissRailRaptorData data;

    static int count = 0;
    static int route = 0;
    static double missingDemand = 0;
    static double routedDemand = 0;

    SBBIntermodalRaptorStopFinder stopFinder;
    RaptorParametersForPerson raptorParametersForPerson;
    RaptorRouteSelector routeSelector = new LeastCostRaptorRouteSelector();
    RaptorInVehicleCostCalculator inVehicleCostCalculator = new DefaultRaptorInVehicleCostCalculator();
    RaptorTransferCostCalculator transferCostCalculator = new DefaultRaptorTransferCostCalculator();
    static List<String> lines = new ArrayList<>();

    public static void main(String[] args) {
        long startTime = System.nanoTime();
        //lines.add("PATHINDEX;PATHLEGINDEX;FROMSTOPPOINTNO;TOSTOPPOINTNO;DEPTIME;ARRTIME");
        MatrixRouterParts matrixRouter = new MatrixRouterParts();
        System.out.println("MatrixRouter: " + ((System.nanoTime() - startTime) / 1_000_000_000) + "s");
        matrixRouter.route();
        //matrixRouter.calculateTest();
        System.out.println("It took " + ((System.nanoTime() - startTime) / 1_000_000_000) + "s");
        System.out.println("Missing connections: " + count);
        System.out.println("Missing demand from connections: " + missingDemand);
        System.out.println("Missing demand from stations: " + matrixRouter.inputDemand.getMissingDemand());
        System.out.println("Routed demand: " + routedDemand);
        System.out.println(route);
        System.out.println("Lines: " + lines.size());
    }

    private void route() {
        if (TRY.equals("Tree")) {
            inputDemand.getTimeList().stream().parallel().forEach(this::calculateTree);
            //List<Double> timeList = new ArrayList<>();
            //for (double i = 0.5; i < 144; i =i+0.5) {
            //    timeList.add(i);
            //}
            //timeList.stream().parallel().forEach(this::calculateTree);
        } else if (TRY.contains("Calc")) {
            inputDemand.getTimeList().stream().parallel().forEach(this::calculateMatrix);
        }
        writeLinkCount();
        writeRoute();
    }

    private void writeRoute() {

        try (BufferedWriter writer = new BufferedWriter(new FileWriter("treeRoutes.csv"))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public void treeRouting() {
        //inputDemand.getTimeList().stream().parallel().forEach(this::calculateTree);
        writeLinkCount();
    }

    public void routingPointToPointTree(List<Integer> startId, List<Integer> endId) {
        inputDemand.getTimeList().stream().parallel().forEach(time -> calculateTreePoint(time, startId, endId));
        writeLinkCount();
    }

    private void calculateTreePoint(Integer time, List<Integer> startId, List<Integer> endId) {
        long startTime = System.nanoTime();
        var raptor = new SwissRailRaptor(data, raptorParametersForPerson, routeSelector, stopFinder, inVehicleCostCalculator, transferCostCalculator);
        double[][] matrix = (double[][]) inputDemand.getOmxFile().getMatrix(time.toString()).getData();
        for (Entry<Integer, Coord> validPotion : inputDemand.getValidPosistions().entrySet()) {
            if (!startId.contains(validPotion.getKey() + 1)) {
                continue;
            }
            Facility startF = afFactory.createActivityFacility(Id.create(1, ActivityFacility.class), validPotion.getValue());
            Map<Id<TransitStopFacility>, TravelInfo> tree = raptor.calcTree(startF, (time - 1) * 600, null, null);
            for (Entry<Integer, Coord> destination : inputDemand.getValidPosistions().entrySet()) {
                if (!endId.contains(destination.getKey() + 1)) {
                    continue;
                }
                double timeDemand = matrix[validPotion.getKey()][destination.getKey()];
                if (timeDemand != 0) {
                    TravelInfo travelInfo = tree.get(data.findNearestStop(destination.getValue().getX(), destination.getValue().getY()).getId());
                    if (travelInfo == null) {
                        count++;
                        missingDemand += timeDemand;
                        continue;
                    }
                    routedDemand += timeDemand;
                    List<? extends PlanElement> legs = RaptorUtils.convertRouteToLegs(travelInfo.getRaptorRoute(),
                        ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class).getTransferWalkMargin());
                    addDemand(timeDemand, legs);
                }
            }
        }
        System.out.println("Matrix: " + time + "; " + ((System.nanoTime() - startTime) / 1_000_000_000) + "s");
    }

    private void calculateTree(Integer time) {
        long startTime = System.nanoTime();
        var raptor = new SwissRailRaptor(data, raptorParametersForPerson, routeSelector, stopFinder, inVehicleCostCalculator, transferCostCalculator);
        //if (time == 0.5) {time = 1.0;}
        double[][] matrix = (double[][]) inputDemand.getOmxFile().getMatrix("" + time.intValue()).getData();
        for (Entry<Integer, Coord> validPotion : inputDemand.getValidPosistions().entrySet()) {
            Facility startF = afFactory.createActivityFacility(Id.create(1, ActivityFacility.class), validPotion.getValue());
            Map<Id<TransitStopFacility>, TravelInfo> tree = raptor.calcTree(startF, (time - 1) * 600, null, null);
            for (Entry<Integer, Coord> destination : inputDemand.getValidPosistions().entrySet()) {
                double timeDemand = matrix[validPotion.getKey()][destination.getKey()];
                if (timeDemand != 0) {
                    route++;
                    TravelInfo travelInfo = tree.get(data.findNearestStop(destination.getValue().getX(), destination.getValue().getY()).getId());
                    if (travelInfo == null) {
                        count++;
                        missingDemand += timeDemand;
                        continue;
                    }
                    int pathlegindex = 1;
                    StringBuilder line = new StringBuilder();
                    if (!(travelInfo.departureStop.equals(Id.create("1311", TransitStopFacility.class)))) {
                        continue;
                    }
                    if (!(data.findNearestStop(destination.getValue().getX(), destination.getValue().getY()).getId().equals(Id.create("2751", TransitStopFacility.class)))) {
                        continue;
                    }
                    for (RoutePart routePart : travelInfo.getRaptorRoute().getParts()) {
                        if (routePart.mode.equals("pt")) {
                            double depatureTime = -1;
                            List<TransitRouteStop> routeStops = routePart.route.getStops();
                            for (TransitRouteStop transitRouteStop : routeStops) {
                                if (transitRouteStop.getStopFacility().getId().equals(routePart.toStop.getId())) {
                                    if (transitRouteStop.getArrivalOffset().isUndefined()) {
                                        depatureTime = routePart.arrivalTime;
                                    } else {
                                        depatureTime = routePart.arrivalTime - transitRouteStop.getArrivalOffset().seconds();
                                    }
                                }
                            }
                            // if (diff == 0) {System.out.println();}
                            line.append(pathlegindex++).append(";")
                                .append(routePart.fromStop.getId()).append(";")
                                .append(routePart.toStop.getId()).append(";")
                                //.append((int) (routePart.boardingTime)).append(";")
                                //.append((int) (routePart.arrivalTime)).append(";");
                                .append((int) depatureTime).append(";");
                        }
                    }
                    if (line.length() == 0) {
                        count++;
                        missingDemand += timeDemand;
                        continue;
                    }
                    line.append(timeDemand);
                    addToLine(line);
                    routedDemand += timeDemand;
                    var tt = travelInfo.getRaptorRoute();
                    List<? extends PlanElement> legs = RaptorUtils.convertRouteToLegs(travelInfo.getRaptorRoute(),
                        ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class).getTransferWalkMargin());
                    addDemand(timeDemand, legs);
                }
            }
        }
        System.out.println("Matrix: " + time + "; " + ((System.nanoTime() - startTime) / 1_000_000_000) + "s");
    }

    private synchronized void addToLine(StringBuilder line) {
        lines.add(line.toString());
    }


    /*private synchronized void addToLine(RoutePart part, int pathindex, int pathlegindex) {
        lines.add(pathindex + ";" + pathlegindex + ";" + part.fromStop.getId() + ";" + part.toStop.getId() + ";" + (int) part.boardingTime + ";" + (int) part.arrivalTime);
    }
     */

    private synchronized void addDemand(double timeDemand, List<? extends PlanElement> legs) {
        for (PlanElement pe : legs) {
            Leg leg = (Leg) pe;
            if (leg.getMode().equals("pt")) {
                List<Id<Link>> linkIds = railTripsAnalyzer.getPtLinkIdsTraveledOn((TransitPassengerRoute) leg.getRoute());
                for (Id<Link> linkId : linkIds) {
                    if (scenario.getNetwork().getLinks().get(linkId).getFromNode().equals(scenario.getNetwork().getLinks().get(linkId).getToNode())) {
                        continue;
                    }
                    if (idDemandStorageMap.containsKey(linkId)) {
                        idDemandStorageMap.get(linkId).increaseDemand(timeDemand);
                    } else {
                        System.out.println("Hilfe");
                        idDemandStorageMap.put(linkId, new DemandStorage2(linkId));
                    }
                }
            }
        }
    }

    public MatrixRouterParts() {
        this.config = ConfigUtils.createConfig();

        SwissRailRaptorConfigGroup srrConfig = ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class);
        List<IntermodalAccessEgressParameterSet> intermodalAccessEgressParameterSets = srrConfig.getIntermodalAccessEgressParameterSets();
        IntermodalAccessEgressParameterSet intermodalAccessEgressParameterSet = new IntermodalAccessEgressParameterSet();
        intermodalAccessEgressParameterSet.setMode("walk");
        intermodalAccessEgressParameterSets.add(intermodalAccessEgressParameterSet);

        PlanCalcScoreConfigGroup pcsConfig = config.planCalcScore();
        ModeParams modeParams = new ModeParams(TransportMode.non_network_walk);
        modeParams.setMarginalUtilityOfTraveling(1);
        pcsConfig.addModeParams(modeParams);

        this.scenario = ScenarioUtils.createScenario(config);
        new TransitScheduleReader(scenario).readFile(schedualFile);
        new MatsimNetworkReader(scenario.getNetwork()).readFile(netwoekFile);

        RaptorStaticConfig raptorStaticConfig = new RaptorStaticConfig();
        raptorStaticConfig.setOptimization(RaptorOptimization.OneToAllRouting);
        SwissRailRaptorData data = SwissRailRaptorData.create(scenario.getTransitSchedule(), null, raptorStaticConfig, scenario.getNetwork(), null);
        this.data = data;

        RaptorIntermodalAccessEgress raptorIntermodalAccessEgress = new DefaultRaptorIntermodalAccessEgress();
        AccessEgressRouteCache accessEgressRouteCache = new AccessEgressRouteCache(null, new SingleModeNetworksCache(), config, scenario);
        SBBIntermodalRaptorStopFinder stopFinder = new SBBIntermodalRaptorStopFinder(config, raptorIntermodalAccessEgress, null, scenario.getTransitSchedule(), accessEgressRouteCache);
        this.stopFinder = stopFinder;

        RaptorParametersForPerson raptorParametersForPerson = new DefaultRaptorParametersForPerson(config);
        this.raptorParametersForPerson = raptorParametersForPerson;
        RaptorRouteSelector routeSelector = new LeastCostRaptorRouteSelector();
        RaptorInVehicleCostCalculator inVehicleCostCalculator = new DefaultRaptorInVehicleCostCalculator();
        RaptorTransferCostCalculator transferCostCalculator = new DefaultRaptorTransferCostCalculator();

        this.swissRailRaptor = new SwissRailRaptor(data, raptorParametersForPerson, routeSelector, stopFinder, inVehicleCostCalculator, transferCostCalculator);
        this.railTripsAnalyzer = new RailTripsAnalyzer(scenario.getTransitSchedule(), scenario.getNetwork());

        this.inputDemand = new InputDemand(columNames, demand, scenario);
    }

    public void routingWithBestPath() {
        inputDemand.getTimeList().stream().parallel().forEach(this::calculateMatrix);
        writeLinkCount();
    }

    private void calculateMatrix(Integer time) {
        long startTime = System.nanoTime();
        var raptor = new SwissRailRaptor(data, raptorParametersForPerson, routeSelector, stopFinder, inVehicleCostCalculator, transferCostCalculator);
        double[][] matrix = (double[][]) inputDemand.getOmxFile().getMatrix(time.toString()).getData();
        for (Entry<Integer, Coord> entryX : inputDemand.getValidPosistions().entrySet()) {
            for (Entry<Integer, Coord> entryY : inputDemand.getValidPosistions().entrySet()) {
                double timeDemand = matrix[entryX.getKey()][entryY.getKey()];
                if (timeDemand != 0) {
                    Facility startF = afFactory.createActivityFacility(Id.create(1, ActivityFacility.class), entryX.getValue());
                    Facility endF = afFactory.createActivityFacility(Id.create(2, ActivityFacility.class), entryY.getValue());
                    RoutingRequest request = DefaultRoutingRequest.withoutAttributes(startF, endF, (time - 1) * 600, null);
                    List<? extends PlanElement> legs = raptor.calcRoute(request);
                    if (legs == null) {
                        //System.out.println("No connection found for " + entryX.getValue() + " to " + entryX.getValue() + " at time " + time + " demand " + timeDemand);
                        //System.out.println("LINESTRING (" + entryX.getValue().getX() + " " + entryX.getValue().getY() + ", " + entryY.getValue().getX() + " " + entryY.getValue().getY() + ");" + time);
                        count++;
                        missingDemand += timeDemand;
                        continue;
                    }

                    int pathlegindex = 1;
                    //int pathindex = getID();
                    StringBuilder line = new StringBuilder();
                    for (PlanElement pe : legs) {
                        Leg leg = (Leg) pe;
                        if (leg.getMode().equals("pt")) {
                            line.append(pathlegindex++).append(";")
                                .append(leg.getRoute().getStartLinkId().toString().split("_")[1]).append(";")
                                .append(leg.getRoute().getEndLinkId().toString().split("_")[1]).append(";")
                                .append((int) leg.getDepartureTime().seconds()).append(";")
                                .append((int) (leg.getDepartureTime().seconds() + leg.getTravelTime().seconds())).append(";");
                        }
                    }
                    if (line.length() == 0) {
                        count++;
                        missingDemand += timeDemand;
                        continue;
                    }
                    addToLine(line.deleteCharAt(line.length() - 1));
                    routedDemand += timeDemand;
                    addDemand(timeDemand, legs);
                }
            }
        }
        System.out.println("Matrix: " + time + "; " + ((System.nanoTime() - startTime) / 1_000_000_000) + "s");
    }

    private void writeLinkCount() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(output))) {
            writer.write("Matsim_Link;Demand;Visum_Link;WKT");
            writer.newLine();
            for (DemandStorage2 demandStorage : idDemandStorageMap.values()) {
                writer.write(demandStorage.toString());
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<Id<Link>, DemandStorage2> createLinkDemandStorage() {
        Map<Id<Link>, DemandStorage2> idDemandStorageMap = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(saveFileInpout))) {
            List<String> header = List.of(reader.readLine().split(";"));
            String line;
            while ((line = reader.readLine()) != null) {
                var linkId = Id.createLinkId(line.split(";")[header.indexOf("Matsim_Link")]);
                idDemandStorageMap.put(linkId, new DemandStorage2(linkId, line.split(";")[header.indexOf("Visum_Link")], line.split(";")[header.indexOf("WKT")]));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return idDemandStorageMap;
    }

    void calculateTest() {
        {
            var raptor = new SwissRailRaptor(data, raptorParametersForPerson, routeSelector, stopFinder, inVehicleCostCalculator, transferCostCalculator);
            Facility startF = afFactory.createActivityFacility(Id.create(1, ActivityFacility.class),
                scenario.getTransitSchedule().getFacilities().get(Id.create(2656, TransitStopFacility.class)).getCoord());
            Map<Id<TransitStopFacility>, TravelInfo> tree = raptor.calcTree(startF, 57060, null, null);
            TravelInfo travelInfo = tree.get(Id.create(3254, TransitStopFacility.class));
            int pathlegindex = 1;
            //int pathindex = getID();
            StringBuilder line = new StringBuilder();
            for (RoutePart routePart : travelInfo.getRaptorRoute().getParts()) {
                if (routePart.mode.equals("pt")) {
                    List<TransitRouteStop> routeStops = routePart.route.getStops();
                    int diff = 0;
                    int diff2 = 0;
                    for (TransitRouteStop transitRouteStop : routeStops) {
                        if (transitRouteStop.getStopFacility().getId().equals(routePart.fromStop.getId())) {
                            if (!(transitRouteStop.getArrivalOffset().isUndefined() || transitRouteStop.getDepartureOffset().isUndefined())) {
                                diff = (int) (transitRouteStop.getDepartureOffset().seconds() - transitRouteStop.getArrivalOffset().seconds());
                            }
                        }
                        if (transitRouteStop.getStopFacility().getId().equals(routePart.toStop.getId())) {
                            if (!(transitRouteStop.getArrivalOffset().isUndefined() || transitRouteStop.getDepartureOffset().isUndefined())) {
                                diff2 = (int) (transitRouteStop.getDepartureOffset().seconds() - transitRouteStop.getArrivalOffset().seconds());
                            }
                        }
                    }
                    // if (diff == 0) {System.out.println();}
                    line.append(pathlegindex++).append(";")
                        .append(routePart.fromStop.getId()).append(";")
                        .append(routePart.toStop.getId()).append(";")
                        .append((int) (routePart.boardingTime) + diff).append(";")
                        .append((int) (routePart.arrivalTime) - diff2).append(";");
                }
            }
            System.out.println(line);
        }
        System.out.println("----------------------------------------");
        var raptor = new SwissRailRaptor(data, raptorParametersForPerson, routeSelector, stopFinder, inVehicleCostCalculator, transferCostCalculator);
        Facility startF = afFactory.createActivityFacility(Id.create(4, ActivityFacility.class),
            scenario.getTransitSchedule().getFacilities().get(Id.create(2656, TransitStopFacility.class)).getCoord());
        Facility endF = afFactory.createActivityFacility(Id.create(1, ActivityFacility.class),
            scenario.getTransitSchedule().getFacilities().get(Id.create(3254, TransitStopFacility.class)).getCoord());
        Map<Id<TransitStopFacility>, TravelInfo> tree = raptor.calcTree(startF, 57000, null, null);
        RoutingRequest request = DefaultRoutingRequest.withoutAttributes(startF, endF, 57000, null);
        List<? extends PlanElement> legs = raptor.calcRoute(request);
        List<RaptorRoute> test = raptor.calcRoutes(startF, endF, 57000, 57000, 58000, null, null);
        StringBuilder line1 = new StringBuilder();
        int pathlegindex = 1;
        for (PlanElement pe : legs) {
            Leg leg = (Leg) pe;
            if (leg.getMode().equals("pt")) {
                line1.append(pathlegindex++).append(";")
                    .append(leg.getRoute().getStartLinkId().toString().split("_")[1]).append(";")
                    .append(leg.getRoute().getEndLinkId().toString().split("_")[1]).append(";")
                    .append((int) leg.getDepartureTime().seconds()).append(";")
                    .append((int) (leg.getDepartureTime().seconds() + leg.getTravelTime().seconds())).append(";");
            }
        }
        System.out.println(line1);
        TravelInfo travelInfo = tree.get(Id.create(2266, TransitStopFacility.class));
        pathlegindex = 1;
        StringBuilder line = new StringBuilder();
        for (RoutePart routePart : travelInfo.getRaptorRoute().getParts()) {
            if (routePart.mode.equals("pt")) {
                line.append(pathlegindex++).append(";")
                    .append(routePart.fromStop.getId()).append(";")
                    .append(routePart.toStop.getId()).append(";")
                    .append((int) routePart.boardingTime).append(";")
                    .append((int) routePart.arrivalTime).append(";");
            }
        }
        System.out.println(line);
    }

}
