package ca.uhn.fhir.jpa.provider.r5;

import ca.uhn.fhir.jpa.dao.DaoConfig;
import ca.uhn.fhir.jpa.dao.r5.BaseJpaR5Test;
import ca.uhn.fhir.jpa.entity.Search;
import ca.uhn.fhir.jpa.model.search.SearchStatusEnum;
import ca.uhn.fhir.jpa.search.SearchCoordinatorSvcImpl;
import ca.uhn.fhir.jpa.util.TestUtil;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.rest.client.interceptor.CapturingInterceptor;
import ca.uhn.fhir.rest.server.exceptions.NotImplementedOperationException;
import ca.uhn.fhir.util.UrlUtil;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.DateTimeType;
import org.hl7.fhir.r5.model.Observation;
import org.hl7.fhir.r5.model.Patient;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;

@SuppressWarnings("Duplicates")
public class ResourceProviderR5Test extends BaseResourceProviderR5Test {

	private CapturingInterceptor myCapturingInterceptor = new CapturingInterceptor();

	@Override
	@After
	public void after() throws Exception {
		super.after();

		myDaoConfig.setAllowMultipleDelete(new DaoConfig().isAllowMultipleDelete());
		myDaoConfig.setAllowExternalReferences(new DaoConfig().isAllowExternalReferences());
		myDaoConfig.setReuseCachedSearchResultsForMillis(new DaoConfig().getReuseCachedSearchResultsForMillis());
		myDaoConfig.setCountSearchResultsUpTo(new DaoConfig().getCountSearchResultsUpTo());
		myDaoConfig.setSearchPreFetchThresholds(new DaoConfig().getSearchPreFetchThresholds());
		myDaoConfig.setAllowContainsSearches(new DaoConfig().isAllowContainsSearches());

		ourClient.unregisterInterceptor(myCapturingInterceptor);
	}

	@Override
	public void before() throws Exception {
		super.before();
		myFhirCtx.setParserErrorHandler(new StrictErrorHandler());

		myDaoConfig.setAllowMultipleDelete(true);
		ourClient.registerInterceptor(myCapturingInterceptor);
		myDaoConfig.setSearchPreFetchThresholds(new DaoConfig().getSearchPreFetchThresholds());
	}

	@Test
	public void testSearchWithContainsLowerCase() {
		myDaoConfig.setAllowContainsSearches(true);

		Patient pt1 = new Patient();
		pt1.addName().setFamily("Elizabeth");
		String pt1id = myPatientDao.create(pt1).getId().toUnqualifiedVersionless().getValue();

		Patient pt2 = new Patient();
		pt2.addName().setFamily("fghijk");
		String pt2id = myPatientDao.create(pt2).getId().toUnqualifiedVersionless().getValue();

		Patient pt3 = new Patient();
		pt3.addName().setFamily("zzzzz");
		myPatientDao.create(pt3).getId().toUnqualifiedVersionless().getValue();


		Bundle output = ourClient
			.search()
			.forResource("Patient")
			.where(Patient.NAME.contains().value("ZAB"))
			.returnBundle(Bundle.class)
			.execute();
		List<String> ids = output.getEntry().stream().map(t -> t.getResource().getIdElement().toUnqualifiedVersionless().getValue()).collect(Collectors.toList());
		assertThat(ids, containsInAnyOrder(pt1id));

		output = ourClient
			.search()
			.forResource("Patient")
			.where(Patient.NAME.contains().value("zab"))
			.returnBundle(Bundle.class)
			.execute();
		ids = output.getEntry().stream().map(t -> t.getResource().getIdElement().toUnqualifiedVersionless().getValue()).collect(Collectors.toList());
		assertThat(ids, containsInAnyOrder(pt1id));

	}

	@Test
	public void testErroredSearchIsNotReused() {
		Patient pt1 = new Patient();
		pt1.addName().setFamily("Hello");
		myPatientDao.create(pt1);

		// Perform the search
		Bundle response0 = ourClient.search()
			.forResource("Patient")
			.where(Patient.NAME.matches().value("Hello"))
			.returnBundle(Bundle.class)
			.execute();
		assertEquals(1, response0.getEntry().size());

		// Perform the search again (should return the same)
		Bundle response1 = ourClient.search()
			.forResource("Patient")
			.where(Patient.NAME.matches().value("Hello"))
			.returnBundle(Bundle.class)
			.execute();
		assertEquals(1, response1.getEntry().size());
		assertEquals(response0.getId(), response1.getId());

		// Pretend the search was errored out
		runInTransaction(()->{
			assertEquals(1L, mySearchEntityDao.count());
			Search search = mySearchEntityDao.findAll().iterator().next();
			search.setStatus(SearchStatusEnum.FAILED);
			search.setFailureMessage("Some Failure Message");
			search.setFailureCode(501);
		});

		// Perform the search again (shouldn't return the errored out search)
		Bundle response3 = ourClient.search()
			.forResource("Patient")
			.where(Patient.NAME.matches().value("Hello"))
			.returnBundle(Bundle.class)
			.execute();
		assertEquals(1, response3.getEntry().size());
		assertNotEquals(response0.getId(), response3.getId());

	}

	@Test
	public void testErroredSearchReturnsAppropriateResponse() {
		Patient pt1 = new Patient();
		pt1.addName().setFamily("Hello");
		myPatientDao.create(pt1);

		Patient pt2 = new Patient();
		pt2.addName().setFamily("Hello");
		myPatientDao.create(pt2);

		// Perform a search for the first page
		Bundle response0 = ourClient.search()
			.forResource("Patient")
			.where(Patient.NAME.matches().value("Hello"))
			.returnBundle(Bundle.class)
			.count(1)
			.execute();
		assertEquals(1, response0.getEntry().size());

		// Pretend the search was errored out
		runInTransaction(()->{
			assertEquals(1L, mySearchEntityDao.count());
			Search search = mySearchEntityDao.findAll().iterator().next();
			search.setStatus(SearchStatusEnum.FAILED);
			search.setFailureMessage("Some Failure Message");
			search.setFailureCode(501);
		});

		// Request the second page
		try {
			ourClient.loadPage().next(response0).execute();
		} catch (NotImplementedOperationException e) {
			assertEquals(501, e.getStatusCode());
			assertThat(e.getMessage(), containsString("Some Failure Message"));
		}

	}

	@Test
	public void testDateNowSyntax() {
		Observation observation = new Observation();
		observation.setEffective(new DateTimeType("1965-08-09"));
		IIdType oid = myObservationDao.create(observation).getId().toUnqualified();
		String nowParam = UrlUtil.escapeUrlParam("%now");
		Bundle output = ourClient
			.search()
			.byUrl("Observation?date=lt" + nowParam)
			.returnBundle(Bundle.class)
			.execute();
		List<IIdType> ids = output.getEntry().stream().map(t -> t.getResource().getIdElement().toUnqualified()).collect(Collectors.toList());
		assertThat(ids, containsInAnyOrder(oid));
	}

	@AfterClass
	public static void afterClassClearContext() {
		TestUtil.clearAllStaticFieldsForUnitTest();
	}

}
