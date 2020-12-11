/*
 * SonarQube
 * Copyright (C) 2009-2020 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.ui.ws;

import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.server.ws.Change;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.System2;
import org.sonar.api.web.page.Page;
import org.sonar.api.web.page.PageDefinition;
import org.sonar.core.extension.CoreExtensionRepository;
import org.sonar.core.platform.PluginInfo;
import org.sonar.core.platform.PluginRepository;
import org.sonar.db.DbClient;
import org.sonar.db.DbTester;
import org.sonar.db.alm.AlmAppInstallDto;
import org.sonar.db.alm.OrganizationAlmBindingDto;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.server.organization.BillingValidations;
import org.sonar.server.organization.BillingValidationsProxy;
import org.sonar.server.organization.DefaultOrganizationProvider;
import org.sonar.server.organization.TestDefaultOrganizationProvider;
import org.sonar.server.project.ProjectDefaultVisibility;
import org.sonar.server.project.Visibility;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ui.PageRepository;
import org.sonar.server.ws.TestRequest;
import org.sonar.server.ws.TestResponse;
import org.sonar.server.ws.WsActionTester;
import org.sonar.updatecenter.common.Version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonar.api.web.page.Page.Scope.ORGANIZATION;
import static org.sonar.db.organization.OrganizationDto.Subscription.FREE;
import static org.sonar.db.organization.OrganizationDto.Subscription.PAID;
import static org.sonar.db.organization.OrganizationDto.Subscription.SONARQUBE;
import static org.sonar.db.permission.GlobalPermission.ADMINISTER;
import static org.sonar.db.permission.GlobalPermission.PROVISION_PROJECTS;
import static org.sonar.test.JsonAssert.assertJson;

public class OrganizationActionTest {
  @Rule
  public DbTester db = DbTester.create(System2.INSTANCE);
  @Rule
  public UserSessionRule userSession = UserSessionRule.standalone();
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private final DbClient dbClient = db.getDbClient();
  private final DefaultOrganizationProvider defaultOrganizationProvider = TestDefaultOrganizationProvider.from(db);
  private final PageRepository pageRepository = mock(PageRepository.class);
  private final BillingValidationsProxy billingValidations = mock(BillingValidationsProxy.class);
  private final ProjectDefaultVisibility projectDefaultVisibility = mock(ProjectDefaultVisibility.class);

  private WsActionTester ws = new WsActionTester(
    new OrganizationAction(dbClient, defaultOrganizationProvider, userSession, pageRepository, billingValidations, projectDefaultVisibility));

  @Before
  public void before() {
    when(projectDefaultVisibility.get(any())).thenReturn(Visibility.PRIVATE);
  }

  @Test
  public void filter_out_admin_pages_when_user_is_not_admin() {
    initWithPages(
      Page.builder("my-plugin/org-page").setName("Organization page").setScope(ORGANIZATION).build(),
      Page.builder("my-plugin/org-admin-page").setName("Organization admin page").setScope(ORGANIZATION).setAdmin(true).build());
    OrganizationDto organization = db.organizations().insert();
    userSession.logIn()
      .addPermission(PROVISION_PROJECTS);

    TestResponse response = executeRequest(organization);

    assertThat(response.getInput())
      .contains("my-plugin/org-page")
      .doesNotContain("my-plugin/org-admin-page");
  }

  @Test
  public void returns_project_visibility_private() {
    OrganizationDto organization = db.organizations().insert();
    userSession.logIn().addPermission(PROVISION_PROJECTS);

    when(projectDefaultVisibility.get(any())).thenReturn(Visibility.PRIVATE);
    TestResponse response = executeRequest(organization);

    assertJson(response.getInput()).isSimilarTo("{\"organization\": {\"projectVisibility\": \"private\"}}");
  }

  @Test
  public void returns_project_visibility_public() {
    when(projectDefaultVisibility.get(any())).thenReturn(Visibility.PUBLIC);
    userSession.logIn().addPermission(PROVISION_PROJECTS);

    OrganizationDto organization = db.organizations().insert();
    TestResponse response = executeRequest(organization);

    assertJson(response.getInput()).isSimilarTo("{\"organization\": {\"projectVisibility\": \"public\"}}");
  }

  @Test
  public void returns_non_admin_and_canUpdateProjectsVisibilityToPrivate_false_when_user_logged_in_but_not_admin_and_extension_returns_true() {
    OrganizationDto defaultOrganization = db.getDefaultOrganization();

    userSession.logIn();
    when(billingValidations.canUpdateProjectVisibilityToPrivate(any(BillingValidations.Organization.class))).thenReturn(true);
    verifyCanUpdateProjectsVisibilityToPrivateResponse(executeRequest(db.getDefaultOrganization()), false);

    userSession.logIn().addPermission(ADMINISTER);
    when(billingValidations.canUpdateProjectVisibilityToPrivate(any(BillingValidations.Organization.class))).thenReturn(false);
    verifyCanUpdateProjectsVisibilityToPrivateResponse(executeRequest(db.getDefaultOrganization()), false);

    userSession.logIn().addPermission(ADMINISTER);
    when(billingValidations.canUpdateProjectVisibilityToPrivate(any(BillingValidations.Organization.class))).thenReturn(true);
    verifyCanUpdateProjectsVisibilityToPrivateResponse(executeRequest(db.getDefaultOrganization()), true);
  }

  @Test
  public void return_FREE_subscription_flag() {
    OrganizationDto freeOrganization = db.organizations().insert(o -> o.setSubscription(FREE));

    TestResponse response = executeRequest(freeOrganization);

    assertJson(response.getInput()).isSimilarTo("{\"organization\": {\"subscription\": \"FREE\"}}");
  }

  @Test
  public void return_SONARQUBE_subscription_flag() {
    OrganizationDto sonarQubeOrganization = db.organizations().insert(o -> o.setSubscription(SONARQUBE));

    TestResponse response = executeRequest(sonarQubeOrganization);

    assertJson(response.getInput()).isSimilarTo("{\"organization\": {\"subscription\": \"SONARQUBE\"}}");
  }

  @Test
  public void return_PAID_subscription_flag() {
    OrganizationDto paidOrganization = db.organizations().insert(o -> o.setSubscription(PAID));

    TestResponse response = executeRequest(paidOrganization);

    assertJson(response.getInput()).isSimilarTo("{\"organization\": {\"subscription\": \"PAID\"}}");
  }

  @Test
  public void return_PAID_subscription_flag_when_not_member_on_private_organization_with_public_project() {
    OrganizationDto paidOrganization = db.organizations().insert(o -> o.setSubscription(PAID));
    db.components().insertPublicProject(paidOrganization);
    userSession.anonymous();

    TestResponse response = executeRequest(paidOrganization);

    assertJson(response.getInput()).isSimilarTo("{\"organization\": {\"subscription\": \"PAID\"}}");
  }

  @Test
  public void return_alm_binding() {
    OrganizationDto organization = db.organizations().insert();
    AlmAppInstallDto almAppInstall = db.alm().insertAlmAppInstall();
    OrganizationAlmBindingDto organizationAlmBinding = db.alm().insertOrganizationAlmBinding(organization, almAppInstall, true);

    TestResponse response = executeRequest(organization);

    assertJson(response.getInput()).isSimilarTo("{\"organization\": " +
      "  {" +
      "    \"alm\": {" +
      "      \"key\": \"" + organizationAlmBinding.getAlm().getId() + "\"," +
      "      \"url\": \"" + organizationAlmBinding.getUrl() + "\"," +
      "      \"membersSync\": " + organizationAlmBinding.isMembersSyncEnable() + "," +
      "      \"personal\": " + almAppInstall.isOwnerUser() +
      "    }" +
      "  }" +
      "}");
  }

  @Test
  public void fail_with_IAE_if_parameter_organization_is_not_specified() {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("The 'organization' parameter is missing");

    executeRequest(null);
  }

  @Test
  public void json_example() {
    initWithPages(
      Page.builder("my-plugin/org-page").setName("Organization page").setScope(ORGANIZATION).build(),
      Page.builder("my-plugin/org-admin-page").setName("Organization admin page").setScope(ORGANIZATION).setAdmin(true).build());
    OrganizationDto organization = db.organizations().insert();
    userSession.logIn()
      .addPermission(ADMINISTER)
      .addPermission(PROVISION_PROJECTS);

    TestResponse response = executeRequest(organization);

    assertJson(response.getInput()).isSimilarTo(ws.getDef().responseExampleAsString());
  }

  @Test
  public void verify_definition() {
    WebService.Action def = ws.getDef();

    assertThat(def.isInternal()).isTrue();
    assertThat(def.description()).isEqualTo("Get information concerning organization navigation for the current user");
    assertThat(def.since()).isEqualTo("6.3");
    assertThat(def.changelog()).extracting(Change::getVersion, Change::getDescription).containsExactlyInAnyOrder(
      tuple("6.4", "The field 'projectVisibility' is added"));

    assertThat(def.params()).hasSize(1);
    WebService.Param organization = def.param("organization");
    assertThat(organization.description()).isEqualTo("the organization key");
    assertThat(organization.isRequired()).isTrue();
    assertThat(organization.exampleValue()).isEqualTo("my-org");
  }

  private void initWithPages(Page... pages) {
    PluginRepository pluginRepository = mock(PluginRepository.class);
    when(pluginRepository.hasPlugin(any())).thenReturn(true);
    when(pluginRepository.getPluginInfo(any())).thenReturn(new PluginInfo("unused").setVersion(Version.create("1.0")));
    CoreExtensionRepository coreExtensionRepository = mock(CoreExtensionRepository.class);
    when(coreExtensionRepository.isInstalled(any())).thenReturn(false);
    PageRepository pageRepository = new PageRepository(pluginRepository, coreExtensionRepository, new PageDefinition[] {context -> {
      for (Page page : pages) {
        context.addPage(page);
      }
    }});
    pageRepository.start();
    ws = new WsActionTester(new OrganizationAction(dbClient, defaultOrganizationProvider, userSession, pageRepository, billingValidations, projectDefaultVisibility));
  }

  private TestResponse executeRequest(@Nullable OrganizationDto organization) {
    TestRequest request = ws.newRequest();
    if (organization != null) {
      request.setParam("organization", organization.getKey());
    }
    return request.execute();
  }

  private static void verifyCanUpdateProjectsVisibilityToPrivateResponse(TestResponse response, boolean canUpdateProjectsVisibilityToPrivate) {
    assertJson(response.getInput())
      .isSimilarTo("{" +
        "  \"organization\": {" +
        "    \"canUpdateProjectsVisibilityToPrivate\": " + canUpdateProjectsVisibilityToPrivate + "," +
        "  }" +
        "}");
  }
}
