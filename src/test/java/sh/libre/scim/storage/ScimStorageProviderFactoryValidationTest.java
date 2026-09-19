package sh.libre.scim.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScimStorageProviderFactoryValidationTest {

    private static final String CUSTOM = "urn:example:custom:2.0:User";

    private ComponentModel modelWith(List<String> mappings) {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.put("user-extension-mappings", mappings);
        model.setConfig(config);
        model.setId("comp");
        return model;
    }

    private void validate(ComponentModel model) {
        var session = mock(KeycloakSession.class);
        var realm = mock(RealmModel.class);
        when(realm.getComponentsStream()).thenReturn(Stream.empty());
        new ScimStorageProviderFactory().validateConfiguration(session, realm, model);
    }

    @Test
    void emptyMappings_passes() {
        assertThatCode(() -> validate(modelWith(List.of())))
            .doesNotThrowAnyException();
    }

    @Test
    void validConfig_passes() {
        assertThatCode(() -> validate(modelWith(List.of("dept = " + CUSTOM + ":department"))))
            .doesNotThrowAnyException();
    }

    @Test
    void malformedRow_throwsComponentValidationException() {
        assertThatThrownBy(() -> validate(modelWith(List.of("no-equals-here"))))
            .isInstanceOf(ComponentValidationException.class);
    }

    @Test
    void unknownType_throwsComponentValidationException() {
        assertThatThrownBy(() -> validate(modelWith(List.of("x = " + CUSTOM + ":y ; type=complex"))))
            .isInstanceOf(ComponentValidationException.class);
    }

    // --- rollback-strategy + bulk-enabled incompatibility ---

    private ComponentModel modelWithRollbackAndBulk(String rollbackStrategy, boolean bulkEnabled) {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.put("user-extension-mappings", List.of());
        if (rollbackStrategy != null) {
            config.putSingle("rollback-strategy", rollbackStrategy);
        }
        config.putSingle("bulk-enabled", String.valueOf(bulkEnabled));
        model.setConfig(config);
        model.setId("comp-rollback");
        return model;
    }

    @Test
    void rollbackAlways_withBulkEnabled_throwsComponentValidationException() {
        assertThatThrownBy(() -> validate(modelWithRollbackAndBulk("always", true)))
            .isInstanceOf(ComponentValidationException.class)
            .hasMessageContaining("rollback-strategy=always")
            .hasMessageContaining("bulk-enabled");
    }

    @Test
    void rollbackCriticalOnly_withBulkEnabled_throwsComponentValidationException() {
        assertThatThrownBy(() -> validate(modelWithRollbackAndBulk("critical-only", true)))
            .isInstanceOf(ComponentValidationException.class)
            .hasMessageContaining("rollback-strategy=critical-only")
            .hasMessageContaining("bulk-enabled");
    }

    @Test
    void rollbackNever_withBulkEnabled_passes() {
        assertThatCode(() -> validate(modelWithRollbackAndBulk("never", true)))
            .doesNotThrowAnyException();
    }

    @Test
    void rollbackAlways_withBulkDisabled_passes() {
        assertThatCode(() -> validate(modelWithRollbackAndBulk("always", false)))
            .doesNotThrowAnyException();
    }

    // --- sync-page-size + sync-page-max-seconds ---

    private ComponentModel modelWithPaging(String pageSize, String pageMaxSeconds) {
        var model = modelWith(List.of());
        if (pageSize != null) {
            model.getConfig().putSingle("sync-page-size", pageSize);
        }
        if (pageMaxSeconds != null) {
            model.getConfig().putSingle("sync-page-max-seconds", pageMaxSeconds);
        }
        return model;
    }

    @Test
    void pagingUnset_passes() {
        assertThatCode(() -> validate(modelWithPaging(null, null))).doesNotThrowAnyException();
    }

    @Test
    void pagingPositive_passes() {
        assertThatCode(() -> validate(modelWithPaging("50", "45"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "0", "-1", "abc", " 5", "2.5"})
    void pageSize_notAPositiveWholeNumber_isRejected(String value) {
        assertThatThrownBy(() -> validate(modelWithPaging(value, null)))
            .isInstanceOf(ComponentValidationException.class)
            .hasMessageContaining("sync-page-size");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "0", "-1", "abc", " 5", "2.5"})
    void pageMaxSeconds_notAPositiveWholeNumber_isRejected(String value) {
        assertThatThrownBy(() -> validate(modelWithPaging(null, value)))
            .isInstanceOf(ComponentValidationException.class)
            .hasMessageContaining("sync-page-max-seconds");
    }

    // --- reading a page setting, which validation cannot guarantee ---

    @Test
    void anUnsetPageSettingReadsAsItsDefault() {
        var model = modelWithPaging(null, null);

        assertThat(ScimStorageProviderFactory.positiveIntSetting(
            model, ScimStorageProviderFactory.SYNC_PAGE_SIZE, 50)).isEqualTo(50);
    }

    @Test
    void aStoredPageSettingIsRead() {
        var model = modelWithPaging("200", null);

        assertThat(ScimStorageProviderFactory.positiveIntSetting(
            model, ScimStorageProviderFactory.SYNC_PAGE_SIZE, 50)).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "0", "-1", "abc", "2.5", "99999999999999999999"})
    void anUnusablePageSettingReadsAsItsDefault(String value) {
        var model = modelWithPaging(value, null);

        assertThat(ScimStorageProviderFactory.positiveIntSetting(
            model, ScimStorageProviderFactory.SYNC_PAGE_SIZE, 50)).isEqualTo(50);
    }
}
