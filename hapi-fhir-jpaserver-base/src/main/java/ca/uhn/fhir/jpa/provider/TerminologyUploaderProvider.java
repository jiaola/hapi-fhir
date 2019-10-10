package ca.uhn.fhir.jpa.provider;

/*
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2019 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.model.util.JpaConstants;
import ca.uhn.fhir.jpa.term.UploadStatistics;
import ca.uhn.fhir.jpa.term.api.ITermLoaderSvc;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.util.AttachmentUtil;
import ca.uhn.fhir.util.ParametersUtil;
import ca.uhn.fhir.util.ValidateUtil;
import org.hl7.fhir.instance.model.api.IBaseParameters;
import org.hl7.fhir.instance.model.api.ICompositeType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.*;

public class TerminologyUploaderProvider extends BaseJpaProvider {

	public static final String PARAM_FILE = "file";
	public static final String PARAM_SYSTEM = "system";
	private static final String RESP_PARAM_CONCEPT_COUNT = "conceptCount";
	private static final String RESP_PARAM_TARGET = "target";
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(TerminologyUploaderProvider.class);
	private static final String RESP_PARAM_SUCCESS = "success";

	@Autowired
	private FhirContext myCtx;
	@Autowired
	private ITermLoaderSvc myTerminologyLoaderSvc;

	/**
	 * Constructor
	 */
	public TerminologyUploaderProvider() {
		this(null, null);
	}

	/**
	 * Constructor
	 */
	public TerminologyUploaderProvider(FhirContext theContext, ITermLoaderSvc theTerminologyLoaderSvc) {
		myCtx = theContext;
		myTerminologyLoaderSvc = theTerminologyLoaderSvc;
	}

	/**
	 * <code>
	 * $upload-external-codesystem
	 * </code>
	 */
	@Operation(typeName = "CodeSystem", name = JpaConstants.OPERATION_UPLOAD_EXTERNAL_CODE_SYSTEM, idempotent = false, returnParameters = {
//		@OperationParam(name = "conceptCount", type = IntegerType.class, min = 1)
	})
	public IBaseParameters uploadSnapshot(
		HttpServletRequest theServletRequest,
		@OperationParam(name = "url", min = 1, typeName = "uri") IPrimitiveType<String> theCodeSystemUrl,
		@OperationParam(name = "localfile", min = 1, max = OperationParam.MAX_UNLIMITED, typeName = "string") List<IPrimitiveType<String>> theLocalFile,
		@OperationParam(name = PARAM_FILE, min = 0, max = OperationParam.MAX_UNLIMITED, typeName = "attachment") List<ICompositeType> theFiles,
		RequestDetails theRequestDetails
	) {

		startRequest(theServletRequest);

		if (theLocalFile == null || theLocalFile.size() == 0) {
			if (theFiles == null || theFiles.size() == 0) {
				throw new InvalidRequestException("No 'localfile' or 'package' parameter, or package had no data");
			}
			for (ICompositeType next : theFiles) {
				ValidateUtil.isTrueOrThrowInvalidRequest(myCtx.getElementDefinition(next.getClass()).getName().equals("Attachment"), "Package must be of type Attachment");
			}
		}

		try {
			List<ITermLoaderSvc.FileDescriptor> localFiles = new ArrayList<>();
			if (theLocalFile != null && theLocalFile.size() > 0) {
				for (IPrimitiveType<String> nextLocalFile : theLocalFile) {
					if (isNotBlank(nextLocalFile.getValue())) {
						ourLog.info("Reading in local file: {}", nextLocalFile.getValue());
						File nextFile = new File(nextLocalFile.getValue());
						if (!nextFile.exists() || !nextFile.isFile()) {
							throw new InvalidRequestException("Unknown file: " + nextFile.getName());
						}
						localFiles.add(new ITermLoaderSvc.FileDescriptor() {
							@Override
							public String getFilename() {
								return nextFile.getAbsolutePath();
							}

							@Override
							public InputStream getInputStream() {
								try {
									return new FileInputStream(nextFile);
								} catch (FileNotFoundException theE) {
									throw new InternalErrorException(theE);
								}
							}
						});
					}
				}
			}

			if (theFiles != null) {
				for (ICompositeType nextPackage : theFiles) {
					final String url = AttachmentUtil.getOrCreateUrl(myCtx, nextPackage).getValueAsString();

					if (isBlank(url)) {
						throw new UnprocessableEntityException("Package is missing mandatory url element");
					}

					localFiles.add(new ITermLoaderSvc.FileDescriptor() {
						@Override
						public String getFilename() {
							return url;
						}

						@Override
						public InputStream getInputStream() {
							byte[] data = AttachmentUtil.getOrCreateData(myCtx, nextPackage).getValue();
							return new ByteArrayInputStream(data);
						}
					});
				}
			}

			String codeSystemUrl = theCodeSystemUrl != null ? theCodeSystemUrl.getValue() : null;
			codeSystemUrl = defaultString(codeSystemUrl);

			UploadStatistics stats;
			switch (codeSystemUrl) {
				case ITermLoaderSvc.SCT_URI:
					stats = myTerminologyLoaderSvc.loadSnomedCt(localFiles, theRequestDetails);
					break;
				case ITermLoaderSvc.LOINC_URI:
					stats = myTerminologyLoaderSvc.loadLoinc(localFiles, theRequestDetails);
					break;
				case ITermLoaderSvc.IMGTHLA_URI:
					stats = myTerminologyLoaderSvc.loadImgthla(localFiles, theRequestDetails);
					break;
				default:
					stats = myTerminologyLoaderSvc.loadCustom(codeSystemUrl, localFiles, theRequestDetails);
					break;
			}

			IBaseParameters retVal = ParametersUtil.newInstance(myCtx);
			ParametersUtil.addParameterToParametersBoolean(myCtx, retVal, RESP_PARAM_SUCCESS, true);
			ParametersUtil.addParameterToParametersInteger(myCtx, retVal, RESP_PARAM_CONCEPT_COUNT, stats.getUpdatedConceptCount());
			ParametersUtil.addParameterToParametersReference(myCtx, retVal, RESP_PARAM_TARGET, stats.getTarget().getValue());

			return retVal;
		} finally {
			endRequest(theServletRequest);
		}
	}

	/**
	 * <code>
	 * $apply-codesystem-delta-add
	 * </code>
	 */
	@Operation(typeName = "CodeSystem", name = JpaConstants.OPERATION_APPLY_CODESYSTEM_DELTA_ADD, idempotent = false, returnParameters = {
	})
	public IBaseParameters uploadDeltaAdd(
		HttpServletRequest theServletRequest,
		@OperationParam(name = PARAM_SYSTEM, min = 1, max = 1, typeName = "uri") IPrimitiveType<String> theSystem,
		@OperationParam(name = PARAM_FILE, min = 0, max = OperationParam.MAX_UNLIMITED, typeName = "attachment") List<ICompositeType> theFiles,
		RequestDetails theRequestDetails
	) {

		startRequest(theServletRequest);
		try {
			validateHaveSystem(theSystem);
			validateHaveFiles(theFiles);

			List<ITermLoaderSvc.FileDescriptor> files = convertAttachmentsToFileDescriptors(theFiles);
			UploadStatistics outcome = myTerminologyLoaderSvc.loadDeltaAdd(theSystem.getValue(), files, theRequestDetails);
			return toDeltaResponse(outcome);
		} finally {
			endRequest(theServletRequest);
		}

	}


	/**
	 * <code>
	 * $apply-codesystem-delta-remove
	 * </code>
	 */
	@Operation(typeName = "CodeSystem", name = JpaConstants.OPERATION_APPLY_CODESYSTEM_DELTA_REMOVE, idempotent = false, returnParameters = {
	})
	public IBaseParameters uploadDeltaRemove(
		HttpServletRequest theServletRequest,
		@OperationParam(name = PARAM_SYSTEM, min = 1, max = 1, typeName = "uri") IPrimitiveType<String> theSystem,
		@OperationParam(name = PARAM_FILE, min = 0, max = OperationParam.MAX_UNLIMITED, typeName = "attachment") List<ICompositeType> theFiles,
		RequestDetails theRequestDetails
	) {

		startRequest(theServletRequest);
		try {
			validateHaveSystem(theSystem);
			validateHaveFiles(theFiles);

			List<ITermLoaderSvc.FileDescriptor> files = convertAttachmentsToFileDescriptors(theFiles);
			UploadStatistics outcome = myTerminologyLoaderSvc.loadDeltaRemove(theSystem.getValue(), files, theRequestDetails);
			return toDeltaResponse(outcome);
		} finally {
			endRequest(theServletRequest);
		}

	}

	private void validateHaveSystem(IPrimitiveType<String> theSystem) {
		if (theSystem == null || isBlank(theSystem.getValueAsString())) {
			throw new InvalidRequestException("Missing mandatory parameter: " + PARAM_SYSTEM);
		}
	}

	private void validateHaveFiles(List<ICompositeType> theFiles) {
		if (theFiles != null) {
			for (ICompositeType nextFile : theFiles) {
				if (!nextFile.isEmpty()) {
					return;
				}
			}
		}
		throw new InvalidRequestException("Missing mandatory parameter: " + PARAM_FILE);
	}

	@Nonnull
	private List<ITermLoaderSvc.FileDescriptor> convertAttachmentsToFileDescriptors(@OperationParam(name = PARAM_FILE, min = 0, max = OperationParam.MAX_UNLIMITED, typeName = "attachment") List<ICompositeType> theFiles) {
		List<ITermLoaderSvc.FileDescriptor> files = new ArrayList<>();
		for (ICompositeType next : theFiles) {
			byte[] nextData = AttachmentUtil.getOrCreateData(myCtx, next).getValue();
			String nextUrl = AttachmentUtil.getOrCreateUrl(myCtx, next).getValue();
			ValidateUtil.isTrueOrThrowInvalidRequest(nextData != null && nextData.length > 0, "Missing Attachment.data value");
			ValidateUtil.isNotBlankOrThrowUnprocessableEntity(nextUrl, "Missing Attachment.url value");

			files.add(new ITermLoaderSvc.ByteArrayFileDescriptor(nextUrl, nextData));
		}
		return files;
	}

	private IBaseParameters toDeltaResponse(UploadStatistics theOutcome) {
		IBaseParameters retVal = ParametersUtil.newInstance(myCtx);
		ParametersUtil.addParameterToParametersInteger(myCtx, retVal, RESP_PARAM_CONCEPT_COUNT, theOutcome.getUpdatedConceptCount());
		ParametersUtil.addParameterToParametersReference(myCtx, retVal, RESP_PARAM_TARGET, theOutcome.getTarget().getValue());
		return retVal;
	}


}
