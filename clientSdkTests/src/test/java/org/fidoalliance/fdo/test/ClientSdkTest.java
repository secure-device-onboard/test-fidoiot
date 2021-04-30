// Copyright 2020 Intel Corporation
// SPDX-License-Identifier: Apache 2.0

package org.fidoalliance.fdo.test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import org.fidoalliance.fdo.test.common.CsvUtils;
import org.fidoalliance.fdo.test.common.TestCase;
import org.fidoalliance.fdo.test.common.TestLogger;
import org.fidoalliance.fdo.test.common.TestProcess;
import org.fidoalliance.fdo.test.common.TestUtil;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.*;
import org.testng.annotations.Test;


/**
 * CI SmokeTest for ClientSDK-FIDOIOT
 */
public class ClientSdkTest extends TestCase {

  @DataProvider(name = "FdoClientSdkTestData")
  public static Object[][] getData() throws Exception {
    TestLogger.info("=====> Reading csv data file.");
    String dataFile = "ClientSdkTest.csv";
    URL resource = ClientSdkTest.class.getClassLoader().getResource(dataFile);
    Assert.assertNotNull(resource, "File " + dataFile + " does not exist;");
    String resourcePath = Paths.get(resource.toURI()).toString();
    TestLogger.info("=====> resourcePath: " + Paths.get(resource.toURI()));
    return CsvUtils.getDataArray(resourcePath);
  }

  /**
   * This method is used to start docker container for PRI-FIDO components.
   */
  @BeforeGroups("fdo_clientsdk_smoketest")
  public void startFdoDockerService() throws IOException, InterruptedException {
    TestLogger.info("=====> Starting FDO Docker Services");
    Path mfgDockerPath = Paths.get(testDir + "/binaries/pri-fidoiot/manufacturer");
    Path ownerDockerPath = Paths.get(testDir + "/binaries/pri-fidoiot/owner");
    Path rvDockerPath = Paths.get(testDir + "/binaries/pri-fidoiot/rv");
    try {
      TestProcess.execute_dockerCmd(mfgDockerPath.toString(), runDockerService + " --build ");
      TestProcess.execute_dockerCmd(rvDockerPath.toString(), runDockerService + " --build ");
      TestProcess.execute_dockerCmd(ownerDockerPath.toString(), runDockerService + " --build ");
      Thread.sleep(fdoDockerUpTimeout.toMillis());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * This method is used to stop docker container for PRI-FIDO components.
   */
  @AfterGroups("fdo_clientsdk_smoketest")
  public void stopFdoDockerService() {
    TestLogger.info("=====> Stopping FDO Docker Services");
    Path mfgDockerPath = Paths.get(testDir + "/binaries/pri-fidoiot/manufacturer");
    Path ownerDockerPath = Paths.get(testDir + "/binaries/pri-fidoiot/owner");
    Path rvDockerPath = Paths.get(testDir + "/binaries/pri-fidoiot/rv");
    try {
      TestProcess.execute_dockerCmd(mfgDockerPath.toString(), downDockerService);
      TestProcess.execute_dockerCmd(ownerDockerPath.toString(), downDockerService);
      TestProcess.execute_dockerCmd(rvDockerPath.toString(), downDockerService);
      Thread.sleep(dockerDownTimeout.toMillis());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void fdoClientSdkTest(String sviEnabled) throws Exception {

    TestLogger.info("=====> testDir: " + testDir);
    Assert.assertNotNull(testDir,
        "The environment variable TEST_DIR must be set for tests to execute properly.");
    Path testPath = Paths.get(testDir);
    Path testDevicePath = Paths.get(testDir + "binaries/client-sdk-fidoiot");

    String[] deviceDiCmd = {"bash", "-cx", "./binaries/client-sdk-fidoiot/linux-client"};

    TestProcess deviceDi = new TestProcess(testPath, deviceDiCmd);
    int deviceResultDi = -1;
    try (TestProcess.Handle hDeviceDi = deviceDi.start()) {
      if (hDeviceDi.waitFor(longTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
        deviceResultDi = hDeviceDi.exitValue();
      }
    }

    if (deviceResultDi != 0) {
      TestLogger.error(
          "Device DI did not complete successfully.Most likely cause is a timeout.Exit value: "
              + deviceResultDi);
    } else {
      TestLogger.info("Device DI completed successfully.Exit value: " + deviceResultDi);
    }
    // Confirm device completed successfully.
    Assert.assertEquals(deviceResultDi, 0,
        "ERROR: Device DI did not exit properly. Exit value: " + deviceResultDi + "; ");

    Thread.sleep(shortTimeout.toMillis());

    String[] shellCmdVoucher = {"bash", "-cx",
        "curl -D - --digest -u apiUser:MfgApiPass123 -XGET "
            + "http://localhost:8039/api/v1/vouchers/abcdef -o ext_voucher"};
    TestProcess shellVoucher = new TestProcess(testPath, shellCmdVoucher);
    try (TestProcess.Handle hShellCmd = shellVoucher.start()) {
      hShellCmd.waitFor(1000, TimeUnit.MILLISECONDS);
    }

    String[] shellCmdTo = {"bash", "-cx",
        "curl -D - --digest -u apiUser:OwnerApiPass123 --header \"Content-Type: application/cbor\" "
            + "--data-binary @ext_voucher http://localhost:8042/api/v1/owner/vouchers/ -o guid"};
    TestProcess shellTo = new TestProcess(testPath, shellCmdTo);
    try (TestProcess.Handle hShellCmdTo = shellTo.start()) {
      hShellCmdTo.waitFor(1000, TimeUnit.MILLISECONDS);
    }

    Thread.sleep(fdoToWait.toMillis());

    String[] deviceToCmd = {"bash", "-cx", "./binaries/client-sdk-fidoiot/linux-client"};

    TestProcess deviceTo = new TestProcess(testPath, deviceToCmd);
    int deviceResultTo = -1;
    try (TestProcess.Handle hDeviceTo = deviceTo.start()) {
      if (hDeviceTo.waitFor(longTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
        deviceResultTo = hDeviceTo.exitValue();
      }
    }

    if (deviceResultTo != 0) {
      TestLogger.error(
          "Device TO did not complete successfully.Most likely cause is a timeout.Exit value: "
              + deviceResultTo);
    } else {
      TestLogger.info("Device TO completed successfully.Exit value: " + deviceResultTo);
    }

    Assert.assertEquals(deviceResultTo, 0,
        "ERROR: Device TO did not exit properly. Exit value: " + deviceResultTo + "; ");

    if (sviEnabled.toLowerCase().equals("true")) {
      String logFileDevice = Paths.get(testDir, resultFile).toString();
      Assert.assertTrue(
          TestUtil.fileContainsString(logFileDevice, "Device onboarded successfully.", true),
          "ERROR: Device: ServiceInfo not processed successfully.");
    }
  }


  @Test(groups = {"fdo_clientsdk_smoketest"}, dataProvider = "FdoClientSdkTestData")
  public void clientSdkTest(String testName,
      String enabled,
      String deviceType,
      String sviEnabled)
      throws Exception {

    TestLogger.info("OS: " + osName + "; OS Version: " + osVersion + "; enabled: " + enabled);

    if (enabled.toLowerCase().equals("false")) {
      throw new SkipException("Skipping disabled test.");
    }

    TestLogger.info("Test Name:" + testName);

    if (testName.equals("Client-sdk-test")) {
      fdoClientSdkTest(sviEnabled);
    } else {
      throw new SkipException("Skipping tests for unknown device type " + deviceType);
    }
  }
}