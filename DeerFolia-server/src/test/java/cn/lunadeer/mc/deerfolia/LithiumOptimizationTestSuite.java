package cn.lunadeer.mc.deerfolia;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite
@SuiteDisplayName("DeerFolia optimization correctness and differential tests")
@SelectPackages("cn.lunadeer.mc.deerfolia.lithium")
@ConfigurationParameter(key = "TestSuite", value = "VanillaFeature")
public class LithiumOptimizationTestSuite {
}
