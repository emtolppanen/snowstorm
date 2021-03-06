package org.snomed.snowstorm.validation;

import com.google.common.collect.Sets;
import io.kaicode.elasticvc.api.BranchCriteria;
import io.kaicode.elasticvc.api.VersionControlHelper;
import org.elasticsearch.index.query.QueryBuilder;
import org.ihtsdo.drools.domain.Constants;
import org.ihtsdo.drools.helper.DescriptionHelper;
import org.ihtsdo.drools.service.TestResourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.snowstorm.core.data.domain.Concepts;
import org.snomed.snowstorm.core.data.domain.Description;
import org.snomed.snowstorm.core.data.domain.Relationship;
import org.snomed.snowstorm.core.data.services.DescriptionService;
import org.snomed.snowstorm.core.data.services.QueryService;
import org.snomed.snowstorm.validation.domain.DroolsDescription;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;

import java.util.*;
import java.util.stream.Collectors;

import static org.elasticsearch.index.query.QueryBuilders.*;

public class DescriptionDroolsValidationService implements org.ihtsdo.drools.service.DescriptionService {

	private final VersionControlHelper versionControlHelper;
	private String branchPath;
	private final BranchCriteria branchCriteria;
	private ElasticsearchOperations elasticsearchTemplate;
	private final DescriptionService descriptionService;
	private final QueryService queryService;
	private final TestResourceProvider testResourceProvider;
	private static Set<String> hierarchyRootIds;
	private Map<String, String> statedHierarchyRootIdCache = Collections.synchronizedMap(new HashMap<>());
	private static final Logger LOGGER = LoggerFactory.getLogger(DescriptionDroolsValidationService.class);

	DescriptionDroolsValidationService(String branchPath,
			BranchCriteria branchCriteria,
			VersionControlHelper versionControlHelper,
			ElasticsearchOperations elasticsearchTemplate,
			DescriptionService descriptionService,
			QueryService queryService, TestResourceProvider testResourceProvider) {

		this.branchPath = branchPath;
		this.branchCriteria = branchCriteria;
		this.versionControlHelper = versionControlHelper;
		this.elasticsearchTemplate = elasticsearchTemplate;
		this.descriptionService = descriptionService;
		this.queryService = queryService;
		this.testResourceProvider = testResourceProvider;
	}

	@Override
	public Set<String> getFSNs(Set<String> conceptIds, String... languageRefsetIds) {
		return descriptionService.findDescriptionsByConceptId(branchPath, conceptIds).stream()
				.filter(d -> d.isActive() && d.getTypeId().equals(Concepts.FSN))
				.map(org.snomed.snowstorm.core.data.domain.Description::getTerm)
				.collect(Collectors.toSet());
	}

	@Override
	public Set<org.ihtsdo.drools.domain.Description> findActiveDescriptionByExactTerm(String exactTerm) {
		return findDescriptionByExactTerm(exactTerm, true);
	}

	@Override
	public Set<org.ihtsdo.drools.domain.Description> findInactiveDescriptionByExactTerm(String exactTerm) {
		return findDescriptionByExactTerm(exactTerm, false);
	}

	private Set<org.ihtsdo.drools.domain.Description> findDescriptionByExactTerm(String exactTerm, boolean active) {
		NativeSearchQuery query = new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteria.getEntityBranchCriteria(Description.class))
						.must(termQuery("active", active))
						.must(termQuery("term", exactTerm))
				)
				.build();
		List<Description> matches = elasticsearchTemplate.search(query, Description.class).get().map(SearchHit::getContent).collect(Collectors.toList());
		return matches.stream()
				.filter(description -> description.getTerm().equals(exactTerm))
				.map(DroolsDescription::new).collect(Collectors.toSet());
	}

	@Override
	public Set<org.ihtsdo.drools.domain.Description> findMatchingDescriptionInHierarchy(org.ihtsdo.drools.domain.Concept concept, org.ihtsdo.drools.domain.Description description) {
		try {
			Set<org.ihtsdo.drools.domain.Description> matchingDescriptions = findActiveDescriptionByExactTerm(description.getTerm())
					.stream().filter(d -> d.getLanguageCode().equals(description.getLanguageCode())).collect(Collectors.toSet());

			if (!matchingDescriptions.isEmpty()) {
				// Filter matching descriptions by hierarchy

				// Find root for this concept
				String conceptHierarchyRootId = findStatedHierarchyRootId(concept);
				if (conceptHierarchyRootId != null) {
					return matchingDescriptions.stream().filter(d -> {
						Set<Long> matchingDescriptionAncestors = queryService.findAncestorIds(branchCriteria, branchPath, true, d.getConceptId());
						return matchingDescriptionAncestors.contains(new Long(conceptHierarchyRootId));
					}).collect(Collectors.toSet());
				}
			}
		} catch (IllegalArgumentException e) {
			LOGGER.error("Drools rule failed.", e);
		}
		return Collections.emptySet();
	}

	@Override
	public String getLanguageSpecificErrorMessage(org.ihtsdo.drools.domain.Description description) {
		if (description == null || description.getAcceptabilityMap() == null || description.getTerm() == null) {
			return "";
		}

		return DescriptionHelper.getLanguageSpecificErrorMessage(description, testResourceProvider.getUsToGbTermMap());
	}

	@Override
	public String getCaseSensitiveWordsErrorMessage(org.ihtsdo.drools.domain.Description description) {
		if (description == null || description.getTerm() == null) {
			return "";
		}

		return DescriptionHelper.getCaseSensitiveWordsErrorMessage(description, testResourceProvider.getCaseSignificantWords());
	}

	@Override
	public Set<String> findParentsNotContainingSemanticTag(org.ihtsdo.drools.domain.Concept concept, String termSemanticTag, String... languageRefsetIds) {
		Set<String> statedParents = new HashSet<>();
		for (org.ihtsdo.drools.domain.Relationship relationship : concept.getRelationships()) {
			if (Constants.IS_A.equals(relationship.getTypeId())
					&& relationship.isActive()
					&& Constants.STATED_RELATIONSHIP.equals(relationship.getCharacteristicTypeId())) {
				statedParents.add(relationship.getDestinationId());
			}
		}

		NativeSearchQuery query = new NativeSearchQueryBuilder()
				.withQuery(boolQuery()
						.must(branchCriteria.getEntityBranchCriteria(org.snomed.snowstorm.core.data.domain.Description.class))
						.must(termsQuery(org.snomed.snowstorm.core.data.domain.Description.Fields.CONCEPT_ID, statedParents))
						.must(termQuery(org.snomed.snowstorm.core.data.domain.Description.Fields.ACTIVE, true))
						.must(termQuery(org.snomed.snowstorm.core.data.domain.Description.Fields.TYPE_ID, Concepts.FSN))
						.mustNot(termQuery(org.snomed.snowstorm.core.data.domain.Description.Fields.TAG, termSemanticTag))
				)
				.build();
		List<Description> descriptions = elasticsearchTemplate.search(query, Description.class).get().map(SearchHit::getContent).collect(Collectors.toList());
		return descriptions.stream().map(Description::getConceptId).collect(Collectors.toSet());
	}

	@Override
	public boolean isRecognisedSemanticTag(String semanticTag) {
		return semanticTag != null && !semanticTag.isEmpty() && testResourceProvider.getSemanticTags().contains(semanticTag);
	}

	private String findStatedHierarchyRootId(org.ihtsdo.drools.domain.Concept concept) {
		String conceptId = concept.getId();
		if (!statedHierarchyRootIdCache.containsKey(conceptId)) {
			statedHierarchyRootIdCache.put(conceptId, doFindStatedHierarchyRootId(concept));
		}
		return statedHierarchyRootIdCache.get(conceptId);
	}

	private String doFindStatedHierarchyRootId(org.ihtsdo.drools.domain.Concept concept) {
		Set<String> statedIsARelationships = concept.getRelationships().stream().filter(r -> r.isActive()
				&& Concepts.STATED_RELATIONSHIP.equals(r.getCharacteristicTypeId())
				&& Concepts.ISA.equals(r.getTypeId())).map(org.ihtsdo.drools.domain.Relationship :: getDestinationId).collect(Collectors.toSet());

		if (statedIsARelationships.isEmpty()) {
			return null;
		}

		Set<String> hierarchyRootIds = findHierarchyRootsOnMAIN();
		Sets.SetView<String> statedHierarchyRoot = Sets.intersection(hierarchyRootIds, statedIsARelationships);
		if (!statedHierarchyRoot.isEmpty()) {
			return statedHierarchyRoot.iterator().next();
		}

		// Search ancestors of stated is-a relationships
		String firstStatedParentId = statedIsARelationships.iterator().next();
		Set<Long> statedAncestors = queryService.findAncestorIds(firstStatedParentId, branchPath, true);
		Set<String> statedAncestorsAsStringArray = statedAncestors.stream().map(String::valueOf).collect(Collectors.toSet());
		statedHierarchyRoot = Sets.intersection(hierarchyRootIds, statedAncestorsAsStringArray);
		if (!statedHierarchyRoot.isEmpty()) {
			return statedHierarchyRoot.iterator().next();
		}

		return null;
	}

	private Set<String> findHierarchyRootsOnMAIN() {
		if (hierarchyRootIds == null) {
			synchronized (DescriptionDroolsValidationService.class) {
				QueryBuilder mainBranchCriteria = versionControlHelper.getBranchCriteria("MAIN").getEntityBranchCriteria(Relationship.class);
				NativeSearchQuery query = new NativeSearchQueryBuilder()
						.withQuery(boolQuery()
								.must(mainBranchCriteria)
								.must(termQuery("active", true))
								.must(termQuery("characteristicTypeId", Concepts.INFERRED_RELATIONSHIP))
								.must(termQuery("destinationId", Concepts.SNOMEDCT_ROOT)))
						.withPageable(PageRequest.of(0, 1000))
						.build();
				List<Relationship> relationships = elasticsearchTemplate.search(query, Relationship.class).get().map(SearchHit::getContent).collect(Collectors.toList());
				hierarchyRootIds = relationships.stream().map(Relationship::getSourceId).collect(Collectors.toSet());
			}
		}
		return hierarchyRootIds;
	}



}
