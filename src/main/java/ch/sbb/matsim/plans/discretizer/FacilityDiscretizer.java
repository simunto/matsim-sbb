package ch.sbb.matsim.plans.discretizer;

import org.matsim.api.core.v01.Id;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;

import java.util.*;

public class FacilityDiscretizer {

    private final ActivityFacilities facilities;
    private final Random random;
    private AbmZoneFacilities zoneData;

    public FacilityDiscretizer(ActivityFacilities facilities)    {
        this.facilities = facilities;
        this.random = MatsimRandom.getRandom();
        assignFacilitiesToZones();
    }

    private void assignFacilitiesToZones()   {
        AbmZoneFacilities zoneData = new AbmZoneFacilities();

        for(ActivityFacility fac : this.facilities.getFacilities().values())  {
            int zoneId = (int) this.facilities.getFacilityAttributes().getAttribute(fac.getId().toString(), "tZone");
            if(zoneId != -99)   {
                Set<String> options = fac.getActivityOptions().keySet();
                for(String opt: options)    {
                    zoneData.addFacility(zoneId, opt, fac.getId());
                }
            }
        }
        this.zoneData = zoneData;
    }

    public ActivityFacility getRandomFacility(int zoneId, String type)  {
        List<Id<ActivityFacility>> facilityList = this.zoneData.getActivityTypes(zoneId).getFacilitiesForType(type);

        Id<ActivityFacility> fid = facilityList.get(this.random.nextInt(facilityList.size()));
        return this.facilities.getFacilities().get(fid);
    }

    // TODO: get facility from weighted list
}
