package outlikealambda.traversal;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static outlikealambda.traversal.Relationships.Types.manual;
import static outlikealambda.traversal.Relationships.Types.ranked;

public final class Relationships {
	private static final String MANUAL = "MANUAL";
	private static final String PROVISIONAL = "PROVISIONAL";
	private static final String AUTHORED = "AUTHORED";
	private static final String RANKED = "RANKED";
	private static final RelationshipType RANKED_TYPE = RelationshipType.withName(RANKED);

	public static class Types {
		public static RelationshipType manual(int topic) {
			return combineTypeAndId(MANUAL, topic);
		}

		public static RelationshipType provisional(int topic) {
			return combineTypeAndId(PROVISIONAL, topic);
		}

		public static RelationshipType authored(int topic) {
			return combineTypeAndId(AUTHORED, topic);
		}

		public static RelationshipType ranked() {
			return RANKED_TYPE;
		}
		private static RelationshipType combineTypeAndId(String s, int id) {
			return RelationshipType.withName(s + "_" + id);
		}
	}

	public static boolean isRanked(Relationship r) {
		return r.isType(ranked());
	}

	public static boolean isManual(Relationship r, int topic) {
		return r.isType(manual(topic));
	}

	private Relationships() {}

	public static class Topic {
		private final RelationshipType manualType;
		private final RelationshipType provisionalType;
		private final RelationshipType authoredType;

		public Topic(int topic) {
			this.manualType = Types.manual(topic);
			this.provisionalType = Types.provisional(topic);
			this.authoredType = Types.authored(topic);
		}

		public boolean isManual(Relationship r) {
			return r.isType(manualType);
		}

		public RelationshipType getManualType() {
			return manualType;
		}

		public boolean isProvisional(Relationship r) {
			return r.isType(provisionalType);
		}

		public RelationshipType getProvisionalType() {
			return provisionalType;
		}

		public RelationshipType getAuthoredType() {
			return authoredType;
		}

		public boolean isRanked(Relationship r) {
			return r.isType(RANKED_TYPE);
		}

		public RelationshipType getRankedType() {
			return RANKED_TYPE;
		}

		public Iterable<Relationship> getAllIncoming(Node n) {
			return getAll(n, Direction.INCOMING);
		}

		public Iterable<Relationship> getAllOutgoing(Node n) {
			return getAll(n, Direction.OUTGOING);
		}

		public Iterable<Relationship> getTargetedIncoming(Node n) {
			return getTargeted(n, Direction.INCOMING);
		}

		public Optional<Relationship> getTargetedOutgoing(Node n) {
			List<Relationship> outgoing = new ArrayList<>();

			getTargeted(n, Direction.OUTGOING).forEach(outgoing::add);

			if (outgoing.size() > 1) {
				throw new IllegalStateException("nodes can only have a single targeted outgoing connection per topic");
			} else if (outgoing.size() == 1) {
				return Optional.of(outgoing.get(0));
			} else {
				return Optional.empty();
			}
		}

		private Iterable<Relationship> getTargeted(Node n, Direction d) {
			return n.getRelationships(d, getManualType(), getProvisionalType());
		}

		private Iterable<Relationship> getAll(Node n, Direction d) {
			return n.getRelationships(d, getManualType(), getProvisionalType(), getRankedType());
		}
	}
}
