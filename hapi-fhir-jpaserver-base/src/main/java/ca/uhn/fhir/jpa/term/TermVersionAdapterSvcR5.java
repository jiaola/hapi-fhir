package ca.uhn.fhir.jpa.term;

import ca.uhn.fhir.jpa.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.term.api.ITermVersionAdapterSvc;
import ca.uhn.fhir.util.UrlUtil;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.ConceptMap;
import org.hl7.fhir.r5.model.ValueSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import javax.annotation.PostConstruct;

import static org.apache.commons.lang3.StringUtils.isBlank;

public class TermVersionAdapterSvcR5 extends BaseTermVersionAdapterSvcImpl implements ITermVersionAdapterSvc {
	private IFhirResourceDao<ConceptMap> myConceptMapResourceDao;
	private IFhirResourceDao<CodeSystem> myCodeSystemResourceDao;
	private IFhirResourceDao<ValueSet> myValueSetResourceDao;

	@Autowired
	private ApplicationContext myAppCtx;

	@SuppressWarnings("unchecked")
	@PostConstruct
	public void start() {
		myCodeSystemResourceDao = (IFhirResourceDao<CodeSystem>) myAppCtx.getBean("myCodeSystemDaoR5");
		myValueSetResourceDao = (IFhirResourceDao<ValueSet>) myAppCtx.getBean("myValueSetDaoR5");
		myConceptMapResourceDao = (IFhirResourceDao<ConceptMap>) myAppCtx.getBean("myConceptMapDaoR5");
	}

	@Override
	public IIdType createOrUpdateCodeSystem(org.hl7.fhir.r4.model.CodeSystem theCodeSystemResource) {
		validateCodeSystemForStorage(theCodeSystemResource);

		CodeSystem codeSystemR4 = org.hl7.fhir.convertors.conv40_50.CodeSystem.convertCodeSystem(theCodeSystemResource);
		if (isBlank(theCodeSystemResource.getIdElement().getIdPart())) {
			String matchUrl = "CodeSystem?url=" + UrlUtil.escapeUrlParam(theCodeSystemResource.getUrl());
			return myCodeSystemResourceDao.update(codeSystemR4, matchUrl).getId();
		} else {
			return myCodeSystemResourceDao.update(codeSystemR4).getId();
		}
	}

	@Override
	public void createOrUpdateConceptMap(org.hl7.fhir.r4.model.ConceptMap theConceptMap) {

		ConceptMap conceptMapR4 = org.hl7.fhir.convertors.conv40_50.ConceptMap.convertConceptMap(theConceptMap);

		if (isBlank(theConceptMap.getIdElement().getIdPart())) {
			String matchUrl = "ConceptMap?url=" + UrlUtil.escapeUrlParam(theConceptMap.getUrl());
			myConceptMapResourceDao.update(conceptMapR4, matchUrl);
		} else {
			myConceptMapResourceDao.update(conceptMapR4);
		}
	}

	@Override
	public void createOrUpdateValueSet(org.hl7.fhir.r4.model.ValueSet theValueSet) {

		ValueSet valueSetR4 = org.hl7.fhir.convertors.conv40_50.ValueSet.convertValueSet(theValueSet);

		if (isBlank(theValueSet.getIdElement().getIdPart())) {
			String matchUrl = "ValueSet?url=" + UrlUtil.escapeUrlParam(theValueSet.getUrl());
			myValueSetResourceDao.update(valueSetR4, matchUrl);
		} else {
			myValueSetResourceDao.update(valueSetR4);
		}
	}

}
