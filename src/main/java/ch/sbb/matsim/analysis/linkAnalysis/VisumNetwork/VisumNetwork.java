package ch.sbb.matsim.analysis.linkAnalysis.VisumNetwork;

import ch.sbb.matsim.csv.CSVWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.core.utils.io.UncheckedIOException;

public class VisumNetwork {

	private static final String HEADER = "$VISION\n" +
			"* Schweizerische Bundesbahnen SBB Personenverkehr Bern 65\n" +
			"* 23.03.18\n" +
			"*\n" +
			"* Tabelle: Versionsblock\n" +
			"*\n" +
			"$VERSION:VERSNR;FILETYPE;LANGUAGE;UNIT\n" +
			"10.000;Net;DEU;KM\n" +
			"\n" +
			"*\n" +
			"* Tabelle: ";
	private static final String[] NODES_COLUMNS = new String[]{
			"$KNOTEN:NR",
			"XKOORD",
			"YKOORD"
	};
	private static final String[] VOLUMES_COLUMNS = new String[]{
			"LINK_ID_SIM",
			"FROMNODENO",
			"TONODENO",
			"VOLUME_SIM"
	};
	private static final String[] LINKS_COLUMNS = new String[]{
            "$STRECKE:NR",
            "VONKNOTNR",
            "NACHKNOTNR",
            "VSYSSET",
            "LAENGE",
            "NBVEHICLES",
            "CAPACITY",
            "FREESPEED",
            "MATSIMID"
    };
    private final HashMap<Tuple<Id<Node>, Id<Node>>, VisumLink> links;
    private final HashMap<Id<Node>, VisumNode> nodes;

    public VisumNetwork() {
        links = new HashMap<>();
        nodes = new HashMap<>();
    }

    public VisumLink getOrCreateLink(Link link) {
        Tuple<Id<Node>, Id<Node>> key = this.getLinkKey(link, false);
        Tuple<Id<Node>, Id<Node>> reverseKey = this.getLinkKey(link, true);
        VisumLink visumLink = this.links.get(key);
		if (visumLink == null) {
			final VisumNode fromNode = this.getNode(link.getFromNode());
			final VisumNode toNode = this.getNode(link.getToNode());

			final VisumLink link1 = new VisumLink(fromNode, toNode);
			final VisumLink link2 = link1.create_opposite_direction();

			this.links.put(key, link1);
			this.links.put(reverseKey, link2);

			visumLink = link1;
		}
		visumLink.setMATSimLink(link);
		return visumLink;
	}

	private VisumNode getNode(final Node node) {
		VisumNode visumNode = this.nodes.get(node.getId());
		if (visumNode == null) {
			visumNode = new VisumNode(node);
			this.nodes.put(node.getId(), visumNode);
		}
		return visumNode;
	}

	private Tuple<Id<Node>, Id<Node>> getLinkKey(final Link link, final Boolean inverse) {
		final Id<Node> toId = link.getToNode().getId();
		final Id<Node> fromId = link.getFromNode().getId();
		if (inverse) {
			return new Tuple<>(toId, fromId);
		} else {
			return new Tuple<>(fromId, toId);
		}
	}

	public void writeUserDefinedAttributes(String filename) {

		final String BENDEFATTR_NET_STRING =
				"$BENUTZERDEFATTRIBUTE:OBJID;ATTID;CODE;NAME;DATENTYP\n" +
						"KNOTEN;MATSIMID;MATSimId;MATSimId;Text\n" +
						"STRECKE;COUNTVALUE;countValue;countValue;Double\n" +
						"STRECKE;MATSIMID;MATSimId;MATSimId;Text\n" +
						"STRECKE;CAPACITY;CAPACITY;CAPACITY;Double\n" +
						"STRECKE;FREESPEED;FREESPEED;FREESPEED;Double\n" +
						"STRECKE;NBVEHICLES;nbVehicles;nbVehicles;Double\n";
		String[] COLUMNS = {};

		try (CSVWriter writer = new CSVWriter(HEADER + "\n" + BENDEFATTR_NET_STRING + "\n", COLUMNS, filename)) {
			writer.writeRow();

		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public void writeLinkVolumesCSV(String filename, Map<Link, Double> linkVolumes) {
		try (CSVWriter writer = new CSVWriter("", VOLUMES_COLUMNS, filename)) {
			for (Map.Entry<Link, Double> entry : linkVolumes.entrySet()) {
				Link link = entry.getKey();
				double volume = entry.getValue();
				String id = link.getId().toString();
				writer.set("LINK_ID_SIM", id);
				final String fromNode = link.getFromNode().getId().toString().startsWith("C_") ? link.getFromNode().getId().toString().substring(2) : link.getFromNode().getId().toString();
				writer.set("FROMNODENO", fromNode);
				final String toNode = link.getToNode().getId().toString().startsWith("C_") ? link.getToNode().getId().toString().substring(2) : link.getToNode().getId().toString();
				writer.set("TONODENO", toNode);
				writer.set("VOLUME_SIM", Double.toString(volume));
				writer.writeRow();
			}

		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public void writeLinksAttributes(String filename, Map<Link, Double> linkVolumes) {

		final String HEADER_LINK = "$VISION\n" +
				"* Schweizerische Bundesbahnen SBB Personenverkehr Bern 65\n" +
				"* 23.03.18\n" +
				"*\n" +
				"* Tabelle: Versionsblock\n" +
				"*\n" +
				"$VERSION:VERSNR;FILETYPE;LANGUAGE;UNIT\n" +
				"10.000;Att;ENG;KM\n" +
				"\n" +
				"*\n" +
				"* Tabelle: Links \n";

		try (CSVWriter writer = new CSVWriter(HEADER_LINK, VOLUMES_COLUMNS, filename)) {
			for (Map.Entry<Link, Double> entry : linkVolumes.entrySet()) {
				Link link = entry.getKey();
				double volume = entry.getValue();
				String id = link.getId().toString();
				writer.set("$LINK:NO", id);
				final String fromNode = link.getFromNode().getId().toString().startsWith("C_") ? link.getFromNode().getId().toString().substring(2) : link.getFromNode().getId().toString();
				writer.set("FROMNODENO", fromNode);
				final String toNode = link.getToNode().getId().toString().startsWith("C_") ? link.getToNode().getId().toString().substring(2) : link.getToNode().getId().toString();
				writer.set("TONODENO", toNode);
				writer.set("NBVEHICLES", Double.toString(volume));
				writer.writeRow();
			}

		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public void writeLinks(String filename) {
		try (CSVWriter writer = new CSVWriter(HEADER + "Strecken\n", LINKS_COLUMNS, filename)) {
			for (VisumLink link : this.links.values()) {
				Link matsimLink = link.getMATSimLink();
				if (matsimLink != null) {
					writer.set("$STRECKE:NR", Integer.toString(link.getId()));
					writer.set("VONKNOTNR", Integer.toString(link.getFromNode().getId()));
					writer.set("NACHKNOTNR", Integer.toString(link.getToNode().getId()));
					writer.set("VSYSSET", "P");
					writer.set("LAENGE", Double.toString(matsimLink.getLength()));
					writer.set("NBVEHICLES", Double.toString(link.getVolume()));
					writer.set("CAPACITY", Double.toString(matsimLink.getCapacity()));
					writer.set("FREESPEED", Double.toString(matsimLink.getFreespeed()));
					writer.set("MATSIMID", matsimLink.getId().toString());
					writer.writeRow();
				}
			}

		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public void write(String folder) {
		this.writeNodes(folder + "/visum_nodes.net");
		this.writeLinks(folder + "/visum_links.net");
		this.writeUserDefinedAttributes(folder + "/visum_userdefined.net");
	}

	public void writeNodes(String filename) {
		try (CSVWriter writer = new CSVWriter(HEADER + "Knoten\n", NODES_COLUMNS, filename)) {
			for (VisumNode node : this.nodes.values()) {
				writer.set("$KNOTEN:NR", Integer.toString(node.getId()));
				writer.set("XKOORD", Double.toString(node.getCoord().getX()));
				writer.set("YKOORD", Double.toString(node.getCoord().getY()));
				writer.writeRow();
			}

		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
