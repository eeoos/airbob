package kr.kro.airbob.domain.commoncode.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CommonCodeGroupRequest {

	public record Create(
		@NotBlank
		@Size(max = 50)
		@Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$")
		String groupCode,
		@NotBlank
		@Size(max = 100)
		String groupName,
		@Size(max = 255)
		String description,
		Boolean isActive
	) {
		public Create {
			if (groupCode != null) {
				groupCode = groupCode.trim();
			}
		}
	}

	public record Update(
		@Size(max = 100)
		@Pattern(regexp = ".*\\S.*")
		String groupName,
		@Size(max = 255)
		String description,
		Boolean isActive
	) {
	}
}
