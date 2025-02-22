package vassar.evaluator.coverage;

import java.util.Map;
import java.util.Properties;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashSet;
import java.io.File;

import evaluator.EvaluatorApp;
import org.orekit.geometry.fov.CircularFieldOfView;
import seakers.orekit.analysis.Analysis;
import seakers.orekit.constellations.Walker;
import seakers.orekit.event.*;
import seakers.orekit.object.CoverageDefinition;
import seakers.orekit.object.CoveragePoint;
import seakers.orekit.object.Instrument;
import seakers.orekit.propagation.PropagatorFactory;
import seakers.orekit.propagation.PropagatorType;
import seakers.orekit.scenario.Scenario;
import seakers.orekit.coverage.access.TimeIntervalArray;
import seakers.orekit.coverage.analysis.AnalysisMetric;
import seakers.orekit.coverage.analysis.GroundEventAnalyzer;

import static seakers.orekit.object.CoverageDefinition.GridStyle.EQUAL_AREA;
//import seakers.orekit.object.fieldofview.NadirSimpleConicalFOV;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.stat.descriptive.DescriptiveStatistics;
import org.hipparchus.util.FastMath;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.frames.TopocentricFrame;
import org.orekit.orbits.KeplerianOrbit;
import org.orekit.orbits.Orbit;
import org.orekit.orbits.PositionAngle;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeComponents;
import org.orekit.time.TimeScalesFactory;
import org.orekit.time.TimeScale;
import org.orekit.time.DateComponents;
import org.orekit.time.DateTimeComponents;
import org.orekit.bodies.BodyShape;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.errors.OrekitException;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.orekit.data.DataProvidersManager;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.SpacecraftState;

/**
 *
 * @author Prachi
 */
public class CoverageAnalysis {

    private int numThreads;
    private int coverageGridGranularity;
    private String cwd; //current working directory
    private Properties propertiesPropagator;
    private CoverageDefinition.GridStyle gridStyle;
    private boolean binaryEncoding;
    private CoverageAnalysisIO coverageAnalysisIO;
    private boolean saveAccessData;
    private AbsoluteDate startDate;
    private AbsoluteDate endDate;

    public CoverageAnalysis(int numThreads, int coverageGridGranularity) throws OrekitException{
        this(numThreads, coverageGridGranularity, true, true);
    }

    public CoverageAnalysis(int numThreads, int coverageGridGranularity, boolean saveAccessData, boolean binaryEncoding) throws OrekitException {
        this(numThreads, coverageGridGranularity, saveAccessData, binaryEncoding, System.getProperty("user.dir"));
    }

    public CoverageAnalysis(int numThreads, int coverageGridGranularity, boolean saveAccessData, boolean binaryEncoding, String cwd) throws OrekitException{

        //setup logger
//        Level level = Level.ALL;
//        Logger.getGlobal().setLevel(level);
//        ConsoleHandler handler = new ConsoleHandler();
//        handler.setLevel(level);
//        Logger.getGlobal().addHandler(handler);
        this.cwd = cwd;

        //if running on a non-US machine, need the line below
        Locale.setDefault(new Locale("en", "US"));

        // Load default dataset saved in the project root directory
        StringBuffer pathBuffer = new StringBuffer();

        final File currrentDir = new File(this.cwd);
        if (currrentDir.exists() && (currrentDir.isDirectory() || currrentDir.getName().endsWith(".zip"))) {
            pathBuffer.append(currrentDir.getAbsolutePath());
            pathBuffer.append(File.separator);
            pathBuffer.append("resources");
        }
        // System.out.println("---- AAAAA " + pathBuffer.toString());
        System.setProperty(DataProvidersManager.OREKIT_DATA_PATH, pathBuffer.toString());

        // Default start date and end date with 7-day run time
        TimeScale utc = TimeScalesFactory.getUTC();
        this.startDate = new AbsoluteDate(2020, 1, 1, 0, 0, 0.000, utc);
        this.endDate = startDate.shiftedBy(7 * 24 * 60 * 60); // 7 days in seconds

        this.numThreads = numThreads;
        this.coverageGridGranularity = coverageGridGranularity;
        this.gridStyle = EQUAL_AREA;
        this.saveAccessData = saveAccessData;
        this.binaryEncoding = binaryEncoding;
        this.coverageAnalysisIO = new CoverageAnalysisIO(this.binaryEncoding, utc);

        reset();
    }

    public void setStartDate(int year, int month, int day, int hour, int minute, double second) throws OrekitException{
        TimeScale utc = TimeScalesFactory.getUTC();
        this.startDate = new AbsoluteDate(year, month, day, hour, minute, second, utc);
    }

    public void setDuration(double durationInSeconds) throws OrekitException{

        this.endDate = this.startDate.shiftedBy(durationInSeconds);

        if(this.endDate.compareTo(this.startDate) < 1){
            throw new IllegalStateException("The end date cannot be set before the start date");
        }
    }

    public void setEndDate(int year, int month, int day, int hour, int minute, double second) throws OrekitException{
        TimeScale utc = TimeScalesFactory.getUTC();
        this.endDate = new AbsoluteDate(year, month, day, hour, minute, second, utc);

        if(this.endDate.compareTo(this.startDate) < 1){
            throw new IllegalStateException("The end date cannot be set before the start date");
        }
    }

    public void reset(){

        //can set the properties/number of resources available
        //to the satellite but we don't need them right now
        //so we are just instantiating it
        this.propertiesPropagator = new Properties();
    }

    public void setCoverageGridGranularity(int granularity){
        this.coverageGridGranularity = granularity;
        this.reset();
    }

    public Map<TopocentricFrame, TimeIntervalArray> getAccesses(double fieldOfView, double inclination, double altitude, int numSats, int numPlanes, String raanLabel) throws OrekitException {
        System.out.println("\n----- GET ACCESSES -----");
        System.out.println(fieldOfView);
        System.out.println(inclination);
        System.out.println(altitude);
        System.out.println(numSats);
        System.out.println(numPlanes);
        System.out.println(raanLabel);
        System.out.println("------------------------\n");
        // EvaluatorApp.sleep(10);

        CoverageAnalysisIO.AccessDataDefinition definition = new CoverageAnalysisIO.AccessDataDefinition(fieldOfView, inclination, altitude, numSats, numPlanes, this.coverageGridGranularity, raanLabel);

        String filename = this.coverageAnalysisIO.getAccessDataFilename(definition);
        if (this.coverageAnalysisIO.getAccessDataFile(filename).exists()) {
            // The access data exists
            //System.out.println("Corresponding data file found");
            return this.coverageAnalysisIO.readAccessData(definition);
        }
        else {
            // Newly compute the accesses
            Map<TopocentricFrame, TimeIntervalArray> fovEvents = this.computeAccesses(fieldOfView, inclination, altitude, numSats, numPlanes, raanLabel);

            if (this.saveAccessData) {
                this.coverageAnalysisIO.writeAccessData(definition, fovEvents);
            }

            return fovEvents;
        }
    }

    private Map<TopocentricFrame, TimeIntervalArray> computeAccesses(double fieldOfView, double inclination, double altitude, int numSats, int numPlanes) throws OrekitException{
        return this.computeAccesses(fieldOfView, inclination, altitude, numSats, numPlanes, null);
    }

    private Map<TopocentricFrame, TimeIntervalArray> computeAccesses(double fieldOfView, double inclination, double altitude, int numSats, int numPlanes, String raanLabel) throws OrekitException{

        double raan = 0.0;

        if(raanLabel != null){
            int hour = 0;
            int minute = 0;
            double second = 0.0;
            boolean skip = false;

            switch (raanLabel){
                case "DD":
                    hour = 6;
                    minute = 30;
                    break;
                case "AM":
                    hour = 10;
                    minute = 0;
                    break;
                case "noon":
                    hour = 12;
                    minute = 0;
                    break;
                case "PM":
                    hour = 14;
                    minute = 0;
                    break;
                default:
                    skip = true;
            }

            if(!skip){
                raan = getRAANForGivenLTANOfSSO(hour, minute, second, altitude, inclination);
            }
        }

        return this.computeAccesses(fieldOfView, inclination, altitude, numSats, numPlanes, raan);
    }

    /**
     * Computes the accesses for satellites sharing the same field of view
     * @param fieldOfView [deg]
     * @param inclination [deg]
     * @param altitude [m]
     * @param numSats
     * @param numPlanes
     * @param raan [deg]
     * @throws OrekitException
     */
    private Map<TopocentricFrame, TimeIntervalArray> computeAccesses(double fieldOfView, double inclination, double altitude, int numSats, int numPlanes, double raan) throws OrekitException{

        long start = System.nanoTime();

        // Reset the properties setting
        this.propertiesPropagator = new Properties();

        TimeScale utc = TimeScalesFactory.getUTC();
        double mu = Constants.WGS84_EARTH_MU; // gravitational coefficient

        //must use IERS_2003 and EME2000 frames to be consistent with STK
        Frame earthFrame = FramesFactory.getITRF(IERSConventions.IERS_2003, true);
        Frame inertialFrame = FramesFactory.getEME2000();

        BodyShape earthShape = new OneAxisEllipsoid(Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING, earthFrame);

        //Enter satellite orbital parameters
        double h = altitude; //altitude in meters
        double a = Constants.WGS84_EARTH_EQUATORIAL_RADIUS+h; //semi-major axis
        double i = FastMath.toRadians(inclination); // inclination given in deg


        // Supported FOV: conical / rectangular

        // 1. Create either a curcular or rectangular FOV
        // CircularFieldOfView
        // PolygonalFieldOfView

        CircularFieldOfView fov = new CircularFieldOfView(Vector3D.PLUS_K, FastMath.toRadians(fieldOfView), 0.0);

        // 2. Give FOV an attitude provider, either nadir or account for off nadir angle





        //define instruments and payload
        ArrayList<Instrument> payload = new ArrayList<>();
        Instrument view1 = new Instrument("view1", fov, 100, 100);
        payload.add(view1);

        //number of total satellites
        int t = numSats;

        //number of planes
        int p = numPlanes;

        //number of phases
        int f = 0;

        Walker walker = new Walker("walker1", payload, a, i, t, p, f, inertialFrame, earthShape, startDate, mu, FastMath.toRadians(raan), 0.0);
        // Walker walker;

        //define coverage params
        //this is coverage with 20 granularity and equal area grid style
        CoverageDefinition coverageDefinition = new CoverageDefinition("covdef", this.coverageGridGranularity, earthShape, this.gridStyle);
        coverageDefinition.assignConstellation(walker);

        //define where to save the coverage - in a map
        HashSet<CoverageDefinition> coverageDefinitionMap = new HashSet<>();
        coverageDefinitionMap.add(coverageDefinition);

        //propagator type
        PropagatorFactory propFactory = new PropagatorFactory(PropagatorType.J2, propertiesPropagator);
        //set the event analyses
        EventAnalysisFactory eventAnalysisFactory = new EventAnalysisFactory(startDate, endDate, inertialFrame, propFactory);
        ArrayList<EventAnalysis> eventanalyses = new ArrayList<>();
        FieldOfViewEventAnalysis fovEventAnalysis = (FieldOfViewEventAnalysis) eventAnalysisFactory.createGroundPointAnalysis(EventAnalysisEnum.FOV, coverageDefinitionMap, propertiesPropagator);
        eventanalyses.add(fovEventAnalysis);

        //set the analyses
        ArrayList<Analysis<?>> analyses = new ArrayList<>();

        Scenario scene = new Scenario.Builder(startDate, endDate, utc).
                eventAnalysis(eventanalyses).analysis(analyses).
                covDefs(coverageDefinitionMap).name("SMAP").properties(propertiesPropagator).
                propagatorFactory(propFactory).build();

        try {
//            Logger.getGlobal().finer(String.format("Running Scenario %s", scene));
//            Logger.getGlobal().finer(String.format("Number of points:     %d", coverageDefinition.getNumberOfPoints()));
//            Logger.getGlobal().finer(String.format("Number of satellites: %d", walker.getSatellites().size()));
            scene.call();
        }
        catch (Exception ex) {
//            Logger.getLogger(CoverageAnalysis.class.getName()).log(Level.SEVERE, null, ex);
//
//            System.out.println("Fail: fov: " + fieldOfView + ", inc: " + inclination + ", alt: " + altitude + ", nSat: " + numSats +
//                    ", nPlane: " + numPlanes + ", raan: " + raan );

            throw new IllegalStateException("scenario failed to complete.");
        }

//        System.out.println("Success: fov: " + fieldOfView + ", inc: " + inclination + ", alt: " + altitude + ", nSat: " + numSats +
//                ", nPlane: " + numPlanes + ", raan: " + raan );

//        Logger.getGlobal().finer(String.format("Done Running Scenario %s", scene));

        Map<TopocentricFrame, TimeIntervalArray> accesses = fovEventAnalysis.getEvents(coverageDefinition);

        //output the time
        long end = System.nanoTime();
//        Logger.getGlobal().finest(String.format("Took %.4f sec", (end - start) / Math.pow(10, 9)));

        return accesses;
    }

    public double getRevisitTime(Map<TopocentricFrame, TimeIntervalArray> accesses){
        return getRevisitTime(accesses,  new double[0], new double[0]);
    }

    public double getRevisitTime(Map<TopocentricFrame, TimeIntervalArray> accesses, double[] latBounds, double[] lonBounds){
        // Method to compute average revisit time from accesses

        GroundEventAnalyzer eventAnalyzer = new GroundEventAnalyzer(accesses);

        DescriptiveStatistics stat;

        if(latBounds.length == 0 && lonBounds.length == 0){
            stat = eventAnalyzer.getStatistics(AnalysisMetric.DURATION, false, this.propertiesPropagator);

        }else{
            stat = eventAnalyzer.getStatistics(AnalysisMetric.DURATION, false, latBounds, lonBounds, this.propertiesPropagator);
        }

        double mean = stat.getMean();
        //System.out.println(String.format("Mean revisit time %s", mean));
        return mean;
    }

    /**
     * Computes a rough estimate of RAAN for a given LTAN
     * @param altitude [m]
     * @param inclination [deg]
     * @return
     * @throws OrekitException
     */
    public double getRAANForGivenLTANOfSSO(int hour, int minute, double second, double altitude, double inclination) throws OrekitException{
        TimeScale utc = TimeScalesFactory.getUTC();
        double mu = Constants.WGS84_EARTH_MU; // gravitation coefficient

        //must use IERS_2003 and EME2000 frames to be consistent with STK
        Frame earthFrame = FramesFactory.getITRF(IERSConventions.IERS_2003, true);
        Frame inertialFrame = FramesFactory.getEME2000();

        BodyShape earthShape = new OneAxisEllipsoid(Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_FLATTENING, earthFrame);

        double minAngle = 10000;
        double optRaan = -1;

        DateTimeComponents startDateTimeComponents = this.startDate.getComponents(utc);

        DateComponents startDateComponents = startDateTimeComponents.getDate();
        TimeComponents startTimeComponents = new TimeComponents(hour, minute, second); // Set the desired LTAN

        AbsoluteDate tempStartDate = new AbsoluteDate(startDateComponents, startTimeComponents, utc);
        AbsoluteDate tempEndDate = this.startDate;

        // If the start date is not before the end date
        while(tempStartDate.compareTo(tempEndDate) > -1){

            // Shift 1 day
            tempStartDate = tempStartDate.shiftedBy( - 24 * 60 * 60);
        }

        for(double raan = 0; raan < 360; raan += 0.1){

            Orbit SSO = new KeplerianOrbit(Constants.WGS84_EARTH_EQUATORIAL_RADIUS + altitude, 0.0001, FastMath.toRadians(inclination),0.0,
                    FastMath.toRadians(raan), 0.0, PositionAngle.MEAN, inertialFrame, tempStartDate, mu);

            GeodeticPoint p = new GeodeticPoint(0, 0, 0);
            CoveragePoint point=new CoveragePoint(earthShape, p, "");

            Vector3D pt1 = SSO.getPVCoordinates().getPosition();
            Vector3D pt2 = point.getPVCoordinates(tempStartDate, inertialFrame).getPosition();
            double angle=Vector3D.angle(pt1, pt2);

            if(angle < minAngle){
                minAngle = angle;

                if(FastMath.toDegrees(angle) < 0.5){
                    Properties propertiesPropagator = new Properties();
                    propertiesPropagator.setProperty("orekit.propagator.mass", "6");
                    propertiesPropagator.setProperty("orekit.propagator.atmdrag", "true");

                    propertiesPropagator.setProperty("orekit.propagator.dragarea", "0.075");
                    propertiesPropagator.setProperty("orekit.propagator.dragcoeff", "2.2");
                    propertiesPropagator.setProperty("orekit.propagator.thirdbody.sun", "true");
                    propertiesPropagator.setProperty("orekit.propagator.thirdbody.moon", "true");
                    propertiesPropagator.setProperty("orekit.propagator.solarpressure", "true");
                    propertiesPropagator.setProperty("orekit.propagator.solararea", "0.058");

                    // PropagatorFactory pf=new PropagatorFactory(PropagatorType.J2,propertiesPropagator);
                    PropagatorFactory pf=new PropagatorFactory(PropagatorType.NUMERICAL,propertiesPropagator);
                    Propagator prop=pf.createPropagator(SSO, 0);
                    SpacecraftState s=prop.propagate(tempStartDate, tempEndDate);
                    KeplerianOrbit orbit=(KeplerianOrbit)s.getOrbit();
                    optRaan = orbit.getRightAscensionOfAscendingNode();
                }
            }
        }

//        Logger.getGlobal().finest(String.format("ANGLE Diff=%.4f", minAngle));
//        Logger.getGlobal().finest(String.format("RAAN=%.4f", optRaan));

        return optRaan;
    }

}
