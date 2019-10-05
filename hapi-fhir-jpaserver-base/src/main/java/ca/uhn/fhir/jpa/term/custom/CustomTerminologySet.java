package ca.uhn.fhir.jpa.term.custom;

import ca.uhn.fhir.jpa.entity.TermCodeSystemVersion;
import ca.uhn.fhir.jpa.entity.TermConcept;
import ca.uhn.fhir.jpa.term.IRecordHandler;
import ca.uhn.fhir.jpa.term.LoadedFileDescriptors;
import ca.uhn.fhir.jpa.term.TerminologyLoaderSvcImpl;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import org.apache.commons.csv.QuoteMode;
import org.apache.commons.lang3.Validate;

import javax.annotation.Nonnull;
import java.util.*;

public class CustomTerminologySet {

	private final int mySize;
	private final ListMultimap<String, TermConcept> myParentCodeToChildrenWithMissingParent;
	private final List<TermConcept> myRootConcepts;

	/**
	 * Constructor for an empty object
	 */
	public CustomTerminologySet() {
		this(0, ArrayListMultimap.create(), new ArrayList<>());
	}

	/**
	 * Constructor
	 */
	public CustomTerminologySet(int theSize, ListMultimap<String, TermConcept> theParentCodeToChildrenWithMissingParent, Collection<TermConcept> theRootConcepts) {
		this(theSize, theParentCodeToChildrenWithMissingParent, new ArrayList<>(theRootConcepts));
	}

	/**
	 * Constructor
	 */
	public CustomTerminologySet(int theSize, ListMultimap<String, TermConcept> theParentCodeToChildrenWithMissingParent, List<TermConcept> theRootConcepts) {
		mySize = theSize;
		myParentCodeToChildrenWithMissingParent = theParentCodeToChildrenWithMissingParent;
		myRootConcepts = theRootConcepts;
	}

	public TermConcept addRootConcept(String theCode) {
		return addRootConcept(theCode, null);
	}

	public TermConcept addRootConcept(String theCode, String theDisplay) {
		Validate.notBlank(theCode, "theCode must not be blank");
		Validate.isTrue(!myRootConcepts.stream().anyMatch(t -> t.getCode().equals(theCode)), "Already have code %s", theCode);
		TermConcept retVal = new TermConcept();
		retVal.setCode(theCode);
		retVal.setDisplay(theDisplay);
		myRootConcepts.add(retVal);
		return retVal;
	}


	public ListMultimap<String, TermConcept> getParentCodeToChildrenWithMissingParent() {
		return Multimaps.unmodifiableListMultimap(myParentCodeToChildrenWithMissingParent);
	}

	public int getSize() {
		return mySize;
	}

	public TermCodeSystemVersion toCodeSystemVersion() {
		TermCodeSystemVersion csv = new TermCodeSystemVersion();

		for (TermConcept next : myRootConcepts) {
			csv.getConcepts().add(next);
		}

		populateVersionToChildCodes(csv, myRootConcepts);

		return csv;
	}

	private void populateVersionToChildCodes(TermCodeSystemVersion theCsv, List<TermConcept> theConcepts) {
		for (TermConcept next : theConcepts) {
			next.setCodeSystemVersion(theCsv);
			populateVersionToChildCodes(theCsv, next.getChildCodes());
		}
	}

	public List<TermConcept> getRootConcepts() {
		return Collections.unmodifiableList(myRootConcepts);
	}


	@Nonnull
	public static CustomTerminologySet load(LoadedFileDescriptors theDescriptors, boolean theFlat) {

		final Map<String, TermConcept> code2concept = new LinkedHashMap<>();
		ArrayListMultimap<String, TermConcept> parentCodeToChildrenWithMissingParent = ArrayListMultimap.create();

		// Concepts
		IRecordHandler conceptHandler = new ConceptHandler(code2concept);
		TerminologyLoaderSvcImpl.iterateOverZipFile(theDescriptors, TerminologyLoaderSvcImpl.CUSTOM_CONCEPTS_FILE, conceptHandler, ',', QuoteMode.NON_NUMERIC, false);
		if (theFlat) {

			return new CustomTerminologySet(code2concept.size(), ArrayListMultimap.create(), code2concept.values());

		} else {

			// Hierarchy
			if (theDescriptors.hasFile(TerminologyLoaderSvcImpl.CUSTOM_HIERARCHY_FILE)) {
				IRecordHandler hierarchyHandler = new HierarchyHandler(code2concept, parentCodeToChildrenWithMissingParent);
				TerminologyLoaderSvcImpl.iterateOverZipFile(theDescriptors, TerminologyLoaderSvcImpl.CUSTOM_HIERARCHY_FILE, hierarchyHandler, ',', QuoteMode.NON_NUMERIC, false);
			}

			// Find root concepts
			List<TermConcept> rootConcepts = new ArrayList<>();
			for (TermConcept nextConcept : code2concept.values()) {
				if (nextConcept.getParents().isEmpty()) {
					rootConcepts.add(nextConcept);
				}
			}

			return new CustomTerminologySet(code2concept.size(), parentCodeToChildrenWithMissingParent, rootConcepts);
		}
	}

}
