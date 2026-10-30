package com.example.consumer;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 只为让消费者项目像个正常 mod（有 modId、能进 mod 列表），不参与被测行为。 */
@Mod("consumer")
public class ConsumerMod {
    private static final Logger LOG = LoggerFactory.getLogger("consumer");

    public ConsumerMod() {
        LOG.info("consumer mod loaded (mcdebug should appear in the same mod list)");
    }
}
