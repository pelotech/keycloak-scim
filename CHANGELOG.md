# Changelog

## [1.0.0-rc.0](https://github.com/pelotech/keycloak-scim/compare/v1.0.0-rc.0...v1.0.0-rc.0) (2026-05-25)


### Features

* **ldap:** propagate LDAP-federated users to SCIM on import ([#2](https://github.com/pelotech/keycloak-scim/issues/2)) ([fc2eee1](https://github.com/pelotech/keycloak-scim/commit/fc2eee1cc5b35d78075247a7c56b161efe9b0ce5))
* **reconcile:** LDAP-deletion reconciler with timestamp ([#3](https://github.com/pelotech/keycloak-scim/issues/3)) ([8f037a0](https://github.com/pelotech/keycloak-scim/commit/8f037a03a96b0845ca93d90c451cdef7f28b00b2))


### Bug fixes

* **deps:** update dependency com.google.guava:guava to v31.1-jre ([2b4c006](https://github.com/pelotech/keycloak-scim/commit/2b4c00630b3c6a3c5fba4d8cc0ea9e4ccfd63f4a))
* **deps:** update dependency de.captaingoldfish:scim-sdk-client to v1.21.1 ([2f4b805](https://github.com/pelotech/keycloak-scim/commit/2f4b8058d79050e0c6d1a36226f50c4f128f9c3c))
* **deps:** update dependency de.captaingoldfish:scim-sdk-client to v1.22.0 ([f4dccee](https://github.com/pelotech/keycloak-scim/commit/f4dccee7fdd82abbb6a33c7d86a4b89da39e46f9))
* **deps:** update dependency de.captaingoldfish:scim-sdk-client to v1.23.0 ([d2c6989](https://github.com/pelotech/keycloak-scim/commit/d2c69894850bde4aa8c10a216ac2f65f0344ad0e))
* **deps:** update dependency de.captaingoldfish:scim-sdk-client to v1.24.0 ([1fbd78d](https://github.com/pelotech/keycloak-scim/commit/1fbd78d807535445403a0c540abdeab5a274730d))
* **deps:** update dependency de.captaingoldfish:scim-sdk-client to v1.25.0 ([41ec231](https://github.com/pelotech/keycloak-scim/commit/41ec2319faa86ef584365eddd3012a0c59300a09))
* **deps:** update dependency de.captaingoldfish:scim-sdk-client to v1.25.1 ([c97abf0](https://github.com/pelotech/keycloak-scim/commit/c97abf08dcd3784d8e6b5dadcd7057862ba595af))
* **deps:** update dependency de.captaingoldfish:scim-sdk-common to v1.21.1 ([b802031](https://github.com/pelotech/keycloak-scim/commit/b80203134bbbea3ea40e26d5383f1d7ddc0aef38))
* **deps:** update dependency de.captaingoldfish:scim-sdk-common to v1.22.0 ([6b84aa5](https://github.com/pelotech/keycloak-scim/commit/6b84aa5ea708287c408c2e2a5c474bc3ca360882))
* **deps:** update dependency de.captaingoldfish:scim-sdk-common to v1.23.0 ([705945f](https://github.com/pelotech/keycloak-scim/commit/705945fcf4af0aca3c6b371aeb1c33753b6524e5))
* **deps:** update dependency de.captaingoldfish:scim-sdk-common to v1.24.0 ([8ae24d5](https://github.com/pelotech/keycloak-scim/commit/8ae24d53042b1a452a21a9cbf03004f958565c5c))
* **deps:** update dependency de.captaingoldfish:scim-sdk-common to v1.25.0 ([f1a38db](https://github.com/pelotech/keycloak-scim/commit/f1a38db07d030b2c0b60ad007ba4b1f1da87c0dd))
* **deps:** update dependency de.captaingoldfish:scim-sdk-common to v1.25.1 ([4cbca93](https://github.com/pelotech/keycloak-scim/commit/4cbca9329ac08fa20413af0744ca1ba7c1552050))
* **deps:** update dependency io.github.resilience4j:resilience4j-retry to v2 ([8b7377c](https://github.com/pelotech/keycloak-scim/commit/8b7377c728908b7159e0a231049b100ac7ca271b))
* **deps:** update dependency io.github.resilience4j:resilience4j-retry to v2 ([33b8dc9](https://github.com/pelotech/keycloak-scim/commit/33b8dc9a07f280bedb219833ff4f27be184b75aa))
* **deps:** update dependency jakarta.persistence:jakarta.persistence-api to v3.2.0 ([1e9e25e](https://github.com/pelotech/keycloak-scim/commit/1e9e25ebb9682e51a3bab7b8f15567cd051d5975))
* **deps:** update dependency jakarta.ws.rs:jakarta.ws.rs-api to v4 ([eb0c06d](https://github.com/pelotech/keycloak-scim/commit/eb0c06d3c69f53166880da91c4dfb4e2d008ec12))
* **deps:** update dependency jakarta.ws.rs:jakarta.ws.rs-api to v4 ([74814ab](https://github.com/pelotech/keycloak-scim/commit/74814abdb0e438ab2613dd25ac20c0642858a33e))
* **deps:** update dependency org.apache.commons:commons-lang3 to v3.15.0 ([a1d5027](https://github.com/pelotech/keycloak-scim/commit/a1d5027fd4e9a30f479fc25ce9160f38bafac3a2))
* **deps:** update dependency org.apache.commons:commons-lang3 to v3.16.0 ([69a8310](https://github.com/pelotech/keycloak-scim/commit/69a83108cf39df6fb9d929f8d015eb483d1b24f0))
* **deps:** update dependency org.apache.commons:commons-lang3 to v3.17.0 ([41f24c6](https://github.com/pelotech/keycloak-scim/commit/41f24c6925b87f54b293e12a53748bc98e04d2fc))
* **deps:** update dependency org.apache.commons:commons-lang3 to v3.18.0 [security] ([#166](https://github.com/pelotech/keycloak-scim/issues/166)) ([eec8ecd](https://github.com/pelotech/keycloak-scim/commit/eec8ecd14971886f0d00f3dc688b587c3002f252))
* **deps:** update dependency org.keycloak:keycloak-core to v20.0.5 ([a7d99cf](https://github.com/pelotech/keycloak-scim/commit/a7d99cf299de68ed3e508352ff5f92f026e554de))
* **deps:** update dependency org.keycloak:keycloak-core to v23 ([d64a5b8](https://github.com/pelotech/keycloak-scim/commit/d64a5b878a1250fa6b45febfecc9f1aa70aef3ec))
* **deps:** update dependency org.keycloak:keycloak-core to v23 ([728ae75](https://github.com/pelotech/keycloak-scim/commit/728ae7524a2285c6885badfc9ca029557ba60c9e))
* **deps:** update dependency org.keycloak:keycloak-core to v23.0.6 ([b3d29d3](https://github.com/pelotech/keycloak-scim/commit/b3d29d36ca5da5d6ba83258a5ebc8250cb088241))
* **deps:** update dependency org.keycloak:keycloak-core to v23.0.7 ([86b4fe8](https://github.com/pelotech/keycloak-scim/commit/86b4fe8621ded98dc531587d8c5800e340b41a06))
* **deps:** update dependency org.keycloak:keycloak-core to v24 ([40a16bb](https://github.com/pelotech/keycloak-scim/commit/40a16bb62cf7b987897976b712130aded0794110))
* **deps:** update dependency org.keycloak:keycloak-core to v24 ([ddf21bf](https://github.com/pelotech/keycloak-scim/commit/ddf21bfb4f4d1e826a4c2165a2be87430d7e5be4))
* **deps:** update dependency org.keycloak:keycloak-core to v24.0.2 ([7171232](https://github.com/pelotech/keycloak-scim/commit/7171232721b872637b06bdb97c503457bf02cd6e))
* **deps:** update dependency org.keycloak:keycloak-core to v24.0.3 ([573ffd3](https://github.com/pelotech/keycloak-scim/commit/573ffd33f7a0d76274a6984f72f5127adacfc5b8))
* **deps:** update dependency org.keycloak:keycloak-core to v24.0.4 ([03ed008](https://github.com/pelotech/keycloak-scim/commit/03ed0080d8b12250dfadafe633fd6fcd38494767))
* **deps:** update dependency org.keycloak:keycloak-core to v24.0.5 ([ebc0233](https://github.com/pelotech/keycloak-scim/commit/ebc02336c4fa412f13b5a2cf690fbb33f67886cc))
* **deps:** update dependency org.keycloak:keycloak-core to v25 ([2810cbf](https://github.com/pelotech/keycloak-scim/commit/2810cbf1af8e3c0089881fbe856d1e0d2f719a75))
* **deps:** update dependency org.keycloak:keycloak-core to v25 ([f47ffda](https://github.com/pelotech/keycloak-scim/commit/f47ffdae474025d6933e2d93e439eb9b0d7f2958))
* **deps:** update dependency org.keycloak:keycloak-core to v25.0.4 ([354e3b1](https://github.com/pelotech/keycloak-scim/commit/354e3b1cc3b0c456f09421e7b9644f416c69ac3f))
* **deps:** update dependency org.keycloak:keycloak-core to v25.0.5 ([686d458](https://github.com/pelotech/keycloak-scim/commit/686d45889a112805a02872b5039abe42fc52a1b6))
* **deps:** update dependency org.keycloak:keycloak-core to v25.0.6 ([7709c3c](https://github.com/pelotech/keycloak-scim/commit/7709c3cf989a6e702252607895240e13202da0a0))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v20.0.5 ([cb32a77](https://github.com/pelotech/keycloak-scim/commit/cb32a77f2dc89b0ec8a0c2b19cb2eae1496ac635))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v23 ([2293010](https://github.com/pelotech/keycloak-scim/commit/22930106aad76886d12d395f08f6e7e7f51c8d62))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v23 ([3681940](https://github.com/pelotech/keycloak-scim/commit/3681940b89e731e2c883bd9249f89a0f36d5500c))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v23.0.6 ([57e3db0](https://github.com/pelotech/keycloak-scim/commit/57e3db03d19e55859f7ad6bc7c47755000830677))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v23.0.7 ([fcc4159](https://github.com/pelotech/keycloak-scim/commit/fcc4159e5e6e136c8916c4f239bc04938bbb1274))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v24 ([07b25cb](https://github.com/pelotech/keycloak-scim/commit/07b25cb55324bcb728d47f66797e7c1a94b62afe))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v24 ([c1ca225](https://github.com/pelotech/keycloak-scim/commit/c1ca225fb0fa791d264fb32b220063a9aeb4b4c9))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v24.0.2 ([645f66c](https://github.com/pelotech/keycloak-scim/commit/645f66c5c4d1523404a795d4011e2884ecee22bd))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v24.0.3 ([5b2fdc7](https://github.com/pelotech/keycloak-scim/commit/5b2fdc7fb6b15448c303d86f5f25101d9eaba4c1))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v24.0.4 ([b9e9bff](https://github.com/pelotech/keycloak-scim/commit/b9e9bffe8815e777e91657dae85e9be3b1c29c51))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v24.0.5 ([9ab0ba4](https://github.com/pelotech/keycloak-scim/commit/9ab0ba4e3649808e22043aa24dc10b9fa6283ff9))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v25 ([70b0e0c](https://github.com/pelotech/keycloak-scim/commit/70b0e0c3097eff47b611e90184cbf08f1bba142b))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v25 ([3ca3adb](https://github.com/pelotech/keycloak-scim/commit/3ca3adba2eaced63cd3c6af298bd22c315d27e5f))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v25.0.4 ([465164d](https://github.com/pelotech/keycloak-scim/commit/465164dbbf1167d8b6b867324bdeca9a4dfbb075))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v25.0.5 ([aca1743](https://github.com/pelotech/keycloak-scim/commit/aca1743d6801620703e4e76c1224039bddf1d5d8))
* **deps:** update dependency org.keycloak:keycloak-model-jpa to v25.0.6 ([37ab7d5](https://github.com/pelotech/keycloak-scim/commit/37ab7d5a3864299498f7b197d46c34d52352a40e))
* **deps:** update dependency org.keycloak:keycloak-model-legacy to v23.0.6 ([f69179f](https://github.com/pelotech/keycloak-scim/commit/f69179ff2d77152fd093c174157d74baa9621a18))
* **deps:** update dependency org.keycloak:keycloak-model-legacy to v23.0.7 ([1e7181b](https://github.com/pelotech/keycloak-scim/commit/1e7181b589dd6203141c8f0c4590c84647c26d34))
* **deps:** update dependency org.keycloak:keycloak-model-legacy to v24 ([4ec6820](https://github.com/pelotech/keycloak-scim/commit/4ec6820d01c682a9a59d960cfa89a63ec52a11cf))
* **deps:** update dependency org.keycloak:keycloak-model-legacy to v24 ([ac48acb](https://github.com/pelotech/keycloak-scim/commit/ac48acb3f7249c5b029d094fce7db1b697b10401))
* **deps:** update dependency org.keycloak:keycloak-model-legacy to v24.0.2 ([d1c39b0](https://github.com/pelotech/keycloak-scim/commit/d1c39b0db0f5ea141778fc8b38a5f3459a4496a5))
* **deps:** update dependency org.keycloak:keycloak-model-legacy to v24.0.3 ([c1bdb89](https://github.com/pelotech/keycloak-scim/commit/c1bdb8956df90d03dd51a15310e1bcabfc84417e))
* **deps:** update dependency org.keycloak:keycloak-model-legacy to v24.0.4 ([51b6e3a](https://github.com/pelotech/keycloak-scim/commit/51b6e3a9345d041ecd434c7252af4c31699d2126))
* **deps:** update dependency org.keycloak:keycloak-model-legacy to v24.0.5 ([a01724b](https://github.com/pelotech/keycloak-scim/commit/a01724b88446289c793a1a1870d99cfddc96f1a9))
* **deps:** update dependency org.keycloak:keycloak-model-legacy-private to v23.0.6 ([8b27b7c](https://github.com/pelotech/keycloak-scim/commit/8b27b7cd4d6e418321794f1823b86f93c8fafbf6))
* **deps:** update dependency org.keycloak:keycloak-model-legacy-private to v23.0.7 ([a5435dc](https://github.com/pelotech/keycloak-scim/commit/a5435dcc76b46ee889d40ee8e9c617c0b13b6ed1))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v20.0.5 ([3b795e7](https://github.com/pelotech/keycloak-scim/commit/3b795e71fc6253f615adda30d0e8627d30a17064))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v23 ([20032be](https://github.com/pelotech/keycloak-scim/commit/20032be2cad44b0cfac668d2021d62e071e4b347))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v23 ([d7c07f5](https://github.com/pelotech/keycloak-scim/commit/d7c07f566fe4b5edb190dd0909ab0276e0f38f59))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v23.0.6 ([0eb7fc4](https://github.com/pelotech/keycloak-scim/commit/0eb7fc43d4581f2b887a1f63d316384bc382e8fb))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v23.0.7 ([960e85a](https://github.com/pelotech/keycloak-scim/commit/960e85aac9d533dddac372eb9545ca648c8d2970))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v24 ([0e35c41](https://github.com/pelotech/keycloak-scim/commit/0e35c41d2f1cc8c603639286e74ae4724833249a))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v24 ([b3c4d33](https://github.com/pelotech/keycloak-scim/commit/b3c4d33856f35e4cbe550d4df1c26762b5b7eeff))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v24.0.2 ([fb84456](https://github.com/pelotech/keycloak-scim/commit/fb84456c19507ee3c12ae6e39ba9f3234604ee80))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v24.0.3 ([382b28a](https://github.com/pelotech/keycloak-scim/commit/382b28a47524aecec3b37d05635673d342f00dbd))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v24.0.4 ([47ab758](https://github.com/pelotech/keycloak-scim/commit/47ab758320901092846290109900716782f935bf))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v24.0.5 ([386caa0](https://github.com/pelotech/keycloak-scim/commit/386caa04be05a8e482f4081f21444ae4102b9d46))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v25 ([4aef5cd](https://github.com/pelotech/keycloak-scim/commit/4aef5cdf9bf053f6fdb5d1e73c4a3a4d664e908b))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v25 ([1b805e4](https://github.com/pelotech/keycloak-scim/commit/1b805e4ae52065b729bd0a825c57968f3f3f2e10))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v25.0.4 ([62e32f1](https://github.com/pelotech/keycloak-scim/commit/62e32f188ac3ba2e3a7b67b9b55bca92746897dc))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v25.0.5 ([f1df820](https://github.com/pelotech/keycloak-scim/commit/f1df820805282d346f7c91d3bc649c9fc9fe8ff2))
* **deps:** update dependency org.keycloak:keycloak-server-spi to v25.0.6 ([d437b4b](https://github.com/pelotech/keycloak-scim/commit/d437b4b8cfb06df85380caea2ff9ded1453af6b8))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v20.0.5 ([4180bbc](https://github.com/pelotech/keycloak-scim/commit/4180bbcd1b7ffaf0bfb81af84a710f49ece1340c))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v23 ([9e71516](https://github.com/pelotech/keycloak-scim/commit/9e715169cd932acab576987751574bc35f3c307c))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v23 ([e4725af](https://github.com/pelotech/keycloak-scim/commit/e4725af32d62accf5579fa98541394cf9f4b1f62))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v23.0.6 ([7267b3d](https://github.com/pelotech/keycloak-scim/commit/7267b3d84ad0f71881bbd13e7bec0d366ecf5ac4))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v23.0.7 ([04324e6](https://github.com/pelotech/keycloak-scim/commit/04324e619833e986542d39c586db7d6d20cf4861))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v24 ([b0f0643](https://github.com/pelotech/keycloak-scim/commit/b0f0643e34625e611f2dd80b79460dcd2d3412c1))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v24 ([6173ac5](https://github.com/pelotech/keycloak-scim/commit/6173ac550e8e65d007eaa8498385686d685a5215))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v24.0.2 ([b08129c](https://github.com/pelotech/keycloak-scim/commit/b08129c7f28e5f0562a7d79b0250347a8a4a210e))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v24.0.3 ([2891e4f](https://github.com/pelotech/keycloak-scim/commit/2891e4f9b2c146529ccdbb57bf25047dcba6bf8f))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v24.0.4 ([5c99964](https://github.com/pelotech/keycloak-scim/commit/5c999642fffb247994610ad91a236cec2378b35f))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v24.0.5 ([12a05c0](https://github.com/pelotech/keycloak-scim/commit/12a05c015f2690b35c9b424a69c3c7a385b3bca9))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v25 ([2256e1f](https://github.com/pelotech/keycloak-scim/commit/2256e1ff737daf7201011724a440f775d9fd5052))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v25 ([9e2b5b4](https://github.com/pelotech/keycloak-scim/commit/9e2b5b4ff2b33dde9e7179f3d3ce213773991ce5))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v25.0.4 ([32912a8](https://github.com/pelotech/keycloak-scim/commit/32912a8c099bd6d3a2fa0a475087144c9660c4ef))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v25.0.5 ([d27ff41](https://github.com/pelotech/keycloak-scim/commit/d27ff41e0cae76d7591813f4d48940f25b0da6f2))
* **deps:** update dependency org.keycloak:keycloak-server-spi-private to v25.0.6 ([eee1920](https://github.com/pelotech/keycloak-scim/commit/eee19205866ff5c325961a497863cb0649c67551))
* **deps:** update dependency org.keycloak:keycloak-services to v20.0.5 ([d9a9ecb](https://github.com/pelotech/keycloak-scim/commit/d9a9ecb1154e09393b51aa28d40708b9dab815d6))
* **deps:** update dependency org.keycloak:keycloak-services to v23 ([1ff17fc](https://github.com/pelotech/keycloak-scim/commit/1ff17fc31eb247fbff52292cf5ec75f87f0edab2))
* **deps:** update dependency org.keycloak:keycloak-services to v23 ([9fa6e29](https://github.com/pelotech/keycloak-scim/commit/9fa6e295e02e23a2b4ea39c09312a2701386c416))
* **deps:** update dependency org.keycloak:keycloak-services to v23.0.6 ([1b7f3a5](https://github.com/pelotech/keycloak-scim/commit/1b7f3a5e0b7684c78de6e3e3fb0580fba058acb4))
* **deps:** update dependency org.keycloak:keycloak-services to v23.0.7 ([711164d](https://github.com/pelotech/keycloak-scim/commit/711164d033bdb40056efd5616e0403608030051e))
* **deps:** update dependency org.keycloak:keycloak-services to v24 ([a107009](https://github.com/pelotech/keycloak-scim/commit/a107009b576e69b4e8a710bfe9e5086a6a912274))
* **deps:** update dependency org.keycloak:keycloak-services to v24 ([2616ec7](https://github.com/pelotech/keycloak-scim/commit/2616ec7e934a125073b2e0feb329416c146c528b))
* **deps:** update dependency org.keycloak:keycloak-services to v24.0.2 ([68e2c87](https://github.com/pelotech/keycloak-scim/commit/68e2c875c2493301784e2adf4ecb871ff144100f))
* **deps:** update dependency org.keycloak:keycloak-services to v24.0.3 ([11448db](https://github.com/pelotech/keycloak-scim/commit/11448dbeb23159ad9bb47f2b159a19748bbe8e73))
* **deps:** update dependency org.keycloak:keycloak-services to v24.0.4 ([bbe6b58](https://github.com/pelotech/keycloak-scim/commit/bbe6b585b554a900cbeaf90baba6c010680c10e5))
* **deps:** update dependency org.keycloak:keycloak-services to v24.0.5 ([65d7ca7](https://github.com/pelotech/keycloak-scim/commit/65d7ca7a19211cd87980145855c4db6733c89fdf))
* **deps:** update dependency org.keycloak:keycloak-services to v25 ([de1a726](https://github.com/pelotech/keycloak-scim/commit/de1a72632d4b641af795562b78416d01949f911c))
* **deps:** update dependency org.keycloak:keycloak-services to v25 ([7a60253](https://github.com/pelotech/keycloak-scim/commit/7a602535fe8ad1dbc72765e0cfe711ecfe371389))
* **deps:** update dependency org.keycloak:keycloak-services to v25.0.4 ([663cf28](https://github.com/pelotech/keycloak-scim/commit/663cf28f0b4972234f6994d1349af4d02e7a858f))
* **deps:** update dependency org.keycloak:keycloak-services to v25.0.5 ([55cb26c](https://github.com/pelotech/keycloak-scim/commit/55cb26ca2544681b5caf10c7f8c2a6d64b1d8843))
* **deps:** update dependency org.keycloak:keycloak-services to v25.0.6 ([2e40684](https://github.com/pelotech/keycloak-scim/commit/2e40684529b9d17cc72eb7d860bb23d038467d7e))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.10.0 ([3df7103](https://github.com/pelotech/keycloak-scim/commit/3df7103bc31bd7ef7028ddf1dcfad7330bc1c9eb))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.11.0 ([b1089e0](https://github.com/pelotech/keycloak-scim/commit/b1089e085b118a41079603208a16ace9e0b099c4))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.11.1 ([b7ef454](https://github.com/pelotech/keycloak-scim/commit/b7ef454bc14277aa11e882f7984fcd5da6385ed2))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.12.0 ([42fb538](https://github.com/pelotech/keycloak-scim/commit/42fb538169a32664cf4991de148f489a9446b69c))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.13.2 ([9f12576](https://github.com/pelotech/keycloak-scim/commit/9f125763d96258b678c26fa039a59929224ce5e1))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.14.0 ([8707270](https://github.com/pelotech/keycloak-scim/commit/8707270fd64d16749c1561be275aa0cc1b9b047b))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.15.0 ([733005e](https://github.com/pelotech/keycloak-scim/commit/733005efa379fc88f8dfae4a59596ea6067bc087))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.16.0 ([c516667](https://github.com/pelotech/keycloak-scim/commit/c5166672242d024ed8c707f989c9ead3256ece54))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.17.0 ([b947704](https://github.com/pelotech/keycloak-scim/commit/b94770425a4a4778df1021dc0992c29c1d45a217))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.18.0 ([422a633](https://github.com/pelotech/keycloak-scim/commit/422a6335459154d1413b9243500a7051f48bec10))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.19.0 ([a417a54](https://github.com/pelotech/keycloak-scim/commit/a417a545fc10d53e6539418608135ae6e14adc4b))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.20.0 ([099fbe8](https://github.com/pelotech/keycloak-scim/commit/099fbe8321a182f30be6ea2c8e90d6e4ead4e907))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.6.4 ([0a82b9a](https://github.com/pelotech/keycloak-scim/commit/0a82b9a79042aa6f380952c837c83fa8afad01aa))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.7.1 ([48f8ce5](https://github.com/pelotech/keycloak-scim/commit/48f8ce57414275d5182c1ec3d5d4a10924bba084))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.8.0 ([ede0f52](https://github.com/pelotech/keycloak-scim/commit/ede0f5261607bdd31830bc1a3308d4d4b570c323))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.8.1 ([11f3ad1](https://github.com/pelotech/keycloak-scim/commit/11f3ad12d3dc6520a05194458f4df05ff7fd19bd))
* **deps:** update dependency org.openrewrite.recipe:rewrite-recipe-bom to v2.9.0 ([65f7a27](https://github.com/pelotech/keycloak-scim/commit/65f7a27f568eb01b1e97216fc70ed322cde52c44))
* don't create entity with empty username ([bd39b29](https://github.com/pelotech/keycloak-scim/commit/bd39b294221a87060aef2d9ad367b00ea27b7443))
* don't print expected exception ([6649cc4](https://github.com/pelotech/keycloak-scim/commit/6649cc430d5f98a2b43f16db41a5113a50c6cf3c))
* format put/delete paths correctly ([1e8d57f](https://github.com/pelotech/keycloak-scim/commit/1e8d57f7010a27ab3da950b91026c850520e3845))
* format put/delete paths correctly ([9da24dd](https://github.com/pelotech/keycloak-scim/commit/9da24ddf3faee51006847d27ef837e3cc9eb3af9)), closes [#132](https://github.com/pelotech/keycloak-scim/issues/132)
* handle emty username & email during sync ([30e1df9](https://github.com/pelotech/keycloak-scim/commit/30e1df99cc184f19c4b06f014b7b66c14bd43cc6))


### Build

* Gradle 8 + Kotlin DSL + Java 21 + `inkules` port ([19956bb](https://github.com/pelotech/keycloak-scim/commit/19956bbad0973d31af12001a2ded3bd9a07f97c2))
* **release:** OCI image packaging for K8s ImageVolume use ([#4](https://github.com/pelotech/keycloak-scim/issues/4)) ([9333530](https://github.com/pelotech/keycloak-scim/commit/9333530d4b49bcf97475e937dfd699a156b82005))


### Documentation

* add LDAP federation design doc; tweak `README` wording ([66601ba](https://github.com/pelotech/keycloak-scim/commit/66601ba47d3b70243433c00ea3cc63a23dc804d9))
* add releasing runbook ([9333530](https://github.com/pelotech/keycloak-scim/commit/9333530d4b49bcf97475e937dfd699a156b82005))
* **auth:** add OAuth 2.0 client_credentials design spec ([0af7ff6](https://github.com/pelotech/keycloak-scim/commit/0af7ff6175cf11a4d462da50b08dff625172165c))
* **auth:** add OAuth 2.0 client_credentials implementation plan ([f6dd3d0](https://github.com/pelotech/keycloak-scim/commit/f6dd3d02ebd3acb64196dd95b44145752070f81e))


### Chores

* kick off release pipeline dry run (`-rc.0`) ([#8](https://github.com/pelotech/keycloak-scim/issues/8)) ([34989ef](https://github.com/pelotech/keycloak-scim/commit/34989ef1e35d336414ac76078560f2e09098d5d4))

## 1.0.0-rc.0 (2026-05-02)


### Features

* **ldap:** propagate LDAP-federated users to SCIM on import ([#2](https://github.com/pelotech/keycloak-scim/issues/2)) ([63d74aa](https://github.com/pelotech/keycloak-scim/commit/63d74aa8f0a4b10256571316d713ef2da5ebd8bc))
* **reconcile:** LDAP-deletion reconciler with timestamp ([#3](https://github.com/pelotech/keycloak-scim/issues/3)) ([f296661](https://github.com/pelotech/keycloak-scim/commit/f296661b8a3556e1aaf5221625dda9630fc43b34))


### Build

* Gradle 8 + Kotlin DSL + Java 21 + `inkules` port ([a3185f4](https://github.com/pelotech/keycloak-scim/commit/a3185f48562da15a91c940e222492ef0a7e9c0a0))
* **release:** OCI image packaging for K8s ImageVolume use ([#4](https://github.com/pelotech/keycloak-scim/issues/4)) ([ee2262c](https://github.com/pelotech/keycloak-scim/commit/ee2262c9558f1af200d52d0fbf90e2118541e631))


### Documentation

* add releasing runbook ([ee2262c](https://github.com/pelotech/keycloak-scim/commit/ee2262c9558f1af200d52d0fbf90e2118541e631))


### Chores

* kick off release pipeline dry run (`-rc.0`) ([#8](https://github.com/pelotech/keycloak-scim/issues/8)) ([f196d9a](https://github.com/pelotech/keycloak-scim/commit/f196d9ae5717de882e7ab3ad60caa4d22be9b582))
