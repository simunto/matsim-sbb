package ch.sbb.matsim.routing.network.AccessTime;

import org.junit.Assert;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;


public class AccessTimeTest {

    public Coord bern = new Coord(600000, 200000); // 20 Minutes access time
    public Coord stleo = new Coord(598345.54, 122581.99); // 2 Minutes access time


    private double assertScoring(Boolean withAccessTime, double constant, double utilityOfTravaleling, double expectedScore, String mode, String modesWithAcess) {
        TestFixture fixture = new TestFixture(bern, stleo, mode, withAccessTime, constant, modesWithAcess);

        fixture.egressParams.setMarginalUtilityOfTraveling(utilityOfTravaleling);
        fixture.accessParams.setMarginalUtilityOfTraveling(utilityOfTravaleling);

        fixture.run();

        Person person = fixture.population.getPersons().get(Id.createPersonId("1"));
        Double score = person.getSelectedPlan().getScore();
        Assert.assertEquals(expectedScore, score, 0.001);
        return score;

    }

    @Test
    public final void testScoringCarNoAccess() {
        assertScoring(false, 0.0, -1.68, -432, "car", "car,bike");
    }

    @Test
    public final void testScoringRideIfAccessForCar() {
        assertScoring(true, 0.0, -1.68, -432, "ride", "car");
    }


    @Test
    public final void testScoringCarAccessForCarConstantNullUtilityNull() {
        assertScoring(true, 0.0, 0.0, -432, "car", "car");
    }

    @Test
    public final void testScoringRideAccessForCarConstantNull() {
        assertScoring(true, 0.0, -1.68, -432.009, "car", "car");
    }

    @Test
    public final void testScoringCarAccessForCar() {
        assertScoring(true, -1.0, -1.68, -433.009, "car", "car");
    }

    @Test
    public final void testScoringRideAccessForCarHigherConstant() {
        assertScoring(true, -10.0, -1.68, -442.009, "car", "car");
    }

    @Test
    public final void testScoringRideAccessForCarHigherConstantHigherUtility() {
        assertScoring(true, -10.0, -30, -874.1666, "car", "car");
    }


    @Test(expected = RuntimeException.class)
    public final void testScoringRideExceptionIfUsingAccess() {
        assertScoring(true, 0.0, -1.68, -432, "ride", "car,ride");
    }

    @Test
    public final void testScoringBike() {
        //without access/egress score = -432
        assertScoring(true, 0.0, -1.68, -432.018, "bike", "bike");
    }

}
