package com.codeagent.test;

/**
 * 零依赖测试入口：编译后由 build.cmd 调用。
 * 原则：断言“返回正确的值”，而非仅“不报错”。
 */
public class AllTests {
    public static void main(String[] args) {
        int failed = 0;
        failed += AgentLoopTest.run();
        // 后续模块测试在此累加：failed += XxxTest.run();

        if (failed == 0) {
            System.out.println("ALL TESTS PASSED");
            System.exit(0);
        } else {
            System.out.println(failed + " TEST(S) FAILED");
            System.exit(1);
        }
    }
}
