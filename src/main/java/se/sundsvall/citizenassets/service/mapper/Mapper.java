package se.sundsvall.citizenassets.service.mapper;

import java.util.ArrayList;
import java.util.HashMap;

import org.springframework.stereotype.Component;

import se.sundsvall.citizenassets.api.model.Asset;
import se.sundsvall.citizenassets.api.model.AssetCreateRequest;
import se.sundsvall.citizenassets.api.model.AsssetUpdateRequest;
import se.sundsvall.citizenassets.integration.db.model.AssetEntity;

@Component
public class Mapper {

	public Asset toDto(final AssetEntity entity) {
		return Asset.builder()
			.withAssetId(entity.getAssetId())
			.withPartyId(entity.getPartyId())
			.withCaseReferenceIds(entity.getCaseReferenceIds())
			.withType(entity.getType())
			.withIssued(entity.getIssued())
			.withValidTo(entity.getValidTo())
			.withStatus(entity.getStatus())
			.withDescription(entity.getDescription())
			.withAdditionalParameters(entity.getAdditionalParameters())
			.build();
	}

	public AssetEntity toEntity(final AssetCreateRequest request) {
		return AssetEntity.builder()
			.withPartyId(request.getPartyId())
			.withAssetId(request.getAssetId())
			.withCaseReferenceIds(request.getCaseReferenceIds())
			.withType(request.getType())
			.withIssued(request.getIssued())
			.withValidTo(request.getValidTo())
			.withStatus(request.getStatus())
			.withDescription(request.getDescription())
			.withAdditionalParameters(request.getAdditionalParameters())
			.build();
	}

	public AssetEntity updateEntity(final AssetEntity old, final AsssetUpdateRequest request) {

		if (request.getAdditionalParameters() != null) {

			final var newMap = new HashMap<>(request.getAdditionalParameters());
			newMap.putAll(old.getAdditionalParameters());
			old.setAdditionalParameters(newMap);
		}

		if (request.getCaseReferenceIds() != null) {

			final var list = new ArrayList<>(request.getCaseReferenceIds());
			list.addAll(old.getCaseReferenceIds());
			old.setCaseReferenceIds(list);
		}

		if (request.getStatus() != null) {
			old.setStatus(request.getStatus());
		}

		if (request.getValidTo() != null) {
			old.setValidTo(request.getValidTo());
		}

		return old;
	}
}
