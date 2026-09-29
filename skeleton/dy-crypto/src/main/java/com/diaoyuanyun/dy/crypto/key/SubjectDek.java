package com.diaoyuanyun.dy.crypto.key;

/**
 * 一把 <b>DEK</b>（Data Encryption Key）及其包裹形态。
 *
 * <h2>信封加密的两层结构（本类的存在理由）</h2>
 * <pre>
 *   明文数据 --(DEK 加密)--> 密文           （DEK 是 per-subject，粒度细）
 *   DEK 字节 --(租户 KEK 加密)--> wrappedDek （KEK 是 per-tenant，粒度粗、可交由 KMS 托管）
 * </pre>
 * 这样做的收益：<b>主体数据加密</b>不需要访问 KMS（只用本地的 DEK），
 * 而 DEK 本身被租户 KEK 保护着，所以库泄露拿不到明文；KEK 只托管在 KMS 里，
 * 轮换 KEK 只需重新包裹 DEK，不必重写全部业务密文。
 *
 * <h2>⚠️ {"仅租户级密钥"} 已被否决（架构规格书 §8.3）</h2>
 * 租户级密钥<b>无法删除租户内的单个用户</b>，因此无法响应 PIPL 删除权。
 * 这就是本模块必须存在 per-subject DEK 的原因。{@link KeyScope} 会用显式枚举
 * 把"这是哪一级的密钥"标出来，避免将来有人把 DEK 悄悄建成租户级
 * —— 那种退化在功能上"照常工作"，只会在删除权被行使时才暴露。
 *
 * <h2>消灭数据副本的纪律</h2>
 * {@link #rawDek()} 返回的是<b>副本</b>。本类不提供"零化内部数组"的接口，
 * 因为零化内部的 byte[] 会给调用方一种错误的安心感（Java 的内存模型下，
 * GC 可能已经把副本留在别的页上，且 {@code String}/对象头无法零化）。
 * 诚实的做法是：<b>DEK 只在加密/解密的那一小段调用栈里以 byte[] 存在</b>，
 * 用完即让局部变量离开作用域；本类不把它缓存成长期对象。
 * 这条限制写在这里，是为了让"我们以为密钥被擦掉了"这句话不被默认为真。
 */
public final class SubjectDek {

    /** DEK 的粒度级别。显式标注，使"降级成租户级"这种退化在类型上可见。 */
    public enum KeyScope {
        /** 每主体一把 —— ADR-12 裁定必须达到的粒度。 */
        PER_SUBJECT,
        /** 每租户一把 —— <b>已被否决</b>，保留枚举值仅为让比较/断言可表达"这不是我们要的"。 */
        PER_TENANT
    }

    private final String tenantId;
    private final String subjectType;
    private final String subjectId;
    private final int version;
    private final byte[] rawDek;
    private final WrappedDek wrapped;

    /** 落库/AAD 里使用的字段名之外的只读视图：主体将 DEK 交给 Layer 2 使用时用。 */
    SubjectDek(String tenantId, String subjectType, String subjectId, int version,
               byte[] rawDek, WrappedDek wrapped) {
        this.tenantId = tenantId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.version = version;
        this.rawDek = rawDek;
        this.wrapped = wrapped;
    }

    /**
     * <b>从持久化材料还原一个 DEK</b> —— 供本模块之外的持久化实现
     * （{@code dy-app} 的 {@code DbSubjectKeyStore}）使用。
     *
     * <h2>为什么必须有一个公开入口，而不是把构造器放开</h2>
     * 构造器是包级私有，因为"随手 new 一个 SubjectDek"意味着<b>可以凭空造出一把密钥</b> ——
     * 那是加密体系里最不该开放的操作。但持久化实现必然在别的模块里（它需要 JDBC，
     * 而本模块刻意零第三方依赖），它必须能把"库里的包裹材料 + 解出来的裸 DEK"
     * 组装成一个 {@link SubjectDek}。故此处给出一个<b>带校验的具名工厂</b>，
     * 而不是把构造器改成 public：
     * <ol>
     *   <li>它校验 {@code wrapped} 的归属三元组/版本与参数<b>逐项一致</b>。
     *       构造器做不到这件事（它拿不到"应当是什么"这个参照）；
     *       工厂能，因为调用方是把两边都给了它。</li>
     *   <li>它的名字（{@code restore}）表明"这是还原，不是新建"——
     *       读代码的人不会误以为可以用它来生成密钥。</li>
     * </ol>
     * 校验的意义：若哪天有人把 A 主体的材料配 B 主体的裸 DEK 传进来，
     * 错误会在此<b>立刻</b>暴露，而不是等到某次解密静默给出错误明文
     * （AEAD 会因 AAD 不匹配而失败，但那时的错误会指向"数据损坏"，指向不到这里）。
     *
     * @param rawDek  已由调用方用包裹时那一代 KEK 解出的裸 DEK
     * @param wrapped 与之配套的包裹材料（其 tenantType/Type/Id/version 必须与前三参数一致）
     */
    public static SubjectDek restore(String tenantId, String subjectType, String subjectId,
                                     int version, byte[] rawDek, WrappedDek wrapped) {
        if (rawDek == null || rawDek.length == 0) {
            throw new IllegalArgumentException("restore 的裸 DEK 为空 —— "
                    + "空密钥会让加密产出可被伪造的密文");
        }
        if (wrapped == null) {
            throw new IllegalArgumentException("restore 缺少包裹材料 —— "
                    + "没有它就无从证明这把 DEK 的出处");
        }
        if (!wrapped.tenantId().equals(tenantId)
                || !wrapped.subjectType().equals(subjectType)
                || !wrapped.subjectId().equals(subjectId)
                || wrapped.version() != version) {
            throw new IllegalArgumentException("restore 的身份不一致："
                    + "参数 = " + tenantId + "/" + subjectType + "/" + subjectId + " v" + version
                    + "，但包裹材料归属 = " + wrapped.display()
                    + " —— 把 A 主体的密钥配上 B 主体的材料，会让解密在别处以"
                    + "『认证失败』的形式暴露，而那看起来像数据被篡改");
        }
        return new SubjectDek(tenantId, subjectType, subjectId, version, rawDek, wrapped);
    }

    public KeyScope scope() {
        // 本类型在构造上只能是 per-subject：它持有具体的 subjectType/subjectId。
        // 保留这个方法是为了让测试能对"粒度"这一命题下断言，而不是靠读代码。
        return KeyScope.PER_SUBJECT;
    }

    public String tenantId() {
        return tenantId;
    }

    public String subjectType() {
        return subjectType;
    }

    public String subjectId() {
        return subjectId;
    }

    /** DEK 版本号。参与 AAD 认证（见 {@link com.diaoyuanyun.dy.crypto.envelope.AadBinding}）。 */
    public int version() {
        return version;
    }

    /**
     * 明文 DEK 字节的<b>副本</b>。
     *
     * <p>调用方必须只在一次加/解密的局部作用域内使用，不得缓存、不得落库、
     * 不得写日志、不得放进任何长生命周期容器。见类注释的"消灭数据副本的纪律"。
     */
    public byte[] rawDek() {
        return rawDek.clone();
    }

    /** 包裹形态（可落库 / 可交给 KMS 托管的那一份）。 */
    public WrappedDek wrapped() {
        return wrapped;
    }

    /** 不暴露任何密钥材料。 */
    @Override
    public String toString() {
        return "SubjectDek[" + tenantId + "/" + subjectType + "/" + subjectId
                + " v" + version + ", scope=" + scope() + "]";
    }
}