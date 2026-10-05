package com.county.coldchain.config;

import com.county.coldchain.domain.DomainEnums.LockStatus;
import com.county.coldchain.domain.DomainEnums.OwnerType;
import com.county.coldchain.domain.DomainEnums.UserRole;
import com.county.coldchain.domain.DomainEnums.ZoneType;
import com.county.coldchain.domain.entity.Category;
import com.county.coldchain.domain.entity.Customer;
import com.county.coldchain.domain.entity.Location;
import com.county.coldchain.domain.entity.Owner;
import com.county.coldchain.domain.entity.PriceLock;
import com.county.coldchain.domain.entity.SysUser;
import com.county.coldchain.domain.entity.Zone;
import com.county.coldchain.repo.CategoryRepository;
import com.county.coldchain.repo.CustomerRepository;
import com.county.coldchain.repo.LocationRepository;
import com.county.coldchain.repo.OwnerRepository;
import com.county.coldchain.repo.PriceLockRepository;
import com.county.coldchain.repo.SysUserRepository;
import com.county.coldchain.repo.ZoneRepository;
import com.county.coldchain.security.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 种子数据：测试账号、货主客户、六大品类温区阈值、三个温区库位、一条锁价协议。 */
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final SysUserRepository userRepository;
    private final OwnerRepository ownerRepository;
    private final CustomerRepository customerRepository;
    private final CategoryRepository categoryRepository;
    private final ZoneRepository zoneRepository;
    private final LocationRepository locationRepository;
    private final PriceLockRepository priceLockRepository;

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }
        seedUsers();
        seedOwnersCustomers();
        seedCategories();
        seedZones();
        seedLocks();
    }

    private void seedUsers() {
        create("admin", "admin123", "系统管理员", UserRole.ADMIN);
        create("inspector", "inspect123", "质检员·李检", UserRole.INSPECTOR);
        create("sorter", "sort123", "分拣员·孙姐", UserRole.SORTER);
        create("dispatcher", "dispatch123", "调度员·赵调度", UserRole.DISPATCHER);
        create("finance", "finance123", "财务·钱会计", UserRole.FINANCE);
        create("sales", "sales123", "销售员·陈小满", UserRole.SALES);
        create("viewer", "view123", "监管只读账号", UserRole.VIEWER);
    }

    private void create(String username, String pwd, String name, UserRole role) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(AuthService.sha256(pwd));
        u.setDisplayName(name);
        u.setRole(role);
        u.setEnabled(true);
        userRepository.save(u);
    }

    private void seedOwnersCustomers() {
        Owner o1 = new Owner();
        o1.setName("老张葡萄园");
        o1.setType(OwnerType.FARMER);
        o1.setContact("张守田");
        o1.setPhone("13800000001");
        o1.setVillage("卧龙镇·沙沟村");
        ownerRepository.save(o1);

        Owner o2 = new Owner();
        o2.setName("青山草莓合作社");
        o2.setType(OwnerType.COOPERATIVE);
        o2.setContact("周社长");
        o2.setPhone("13800000002");
        o2.setVillage("青山乡·莓香村");
        ownerRepository.save(o2);

        Owner o3 = new Owner();
        o3.setName("鲁南批发·王德福");
        o3.setType(OwnerType.WHOLESALER);
        o3.setContact("王德福");
        o3.setPhone("13800000003");
        o3.setVillage("县批发市场");
        ownerRepository.save(o3);

        Customer c1 = new Customer();
        c1.setName("县城鲜果批发行");
        c1.setContact("马老板");
        c1.setPhone("13900000001");
        customerRepository.save(c1);

        Customer c2 = new Customer();
        c2.setName("喜连连连锁超市");
        c2.setContact("采购部·林琳");
        c2.setPhone("13900000002");
        customerRepository.save(c2);

        Customer c3 = new Customer();
        c3.setName("佳美食品加工厂");
        c3.setContact("原料科·高平");
        c3.setPhone("13900000003");
        c3.setProcessingChannel(true);
        customerRepository.save(c3);

        Customer c4 = new Customer();
        c4.setName("社区团购·张团长");
        c4.setContact("张团长");
        c4.setPhone("13900000004");
        customerRepository.save(c4);
    }

    private void seedCategories() {
        cat("GRAPE", "葡萄（阳光玫瑰）", new BigDecimal("-1"), new BigDecimal("8"),
                ZoneType.CHILLED, "0.35", "0.60", "0.03", "12.0", "8.0", "3.0");
        cat("STRAWBERRY", "草莓", new BigDecimal("0"), new BigDecimal("4"),
                ZoneType.CHILLED, "0.40", "0.80", "0.05", "25.0", "15.0", "6.0");
        cat("LEAFY", "叶菜（上海青）", new BigDecimal("0"), new BigDecimal("6"),
                ZoneType.REFRIGERATED, "0.20", "0.30", "0.04", "6.0", "3.5", "1.0");
        cat("FRESH_MEAT", "鲜肉（冷鲜猪肉）", new BigDecimal("-1"), new BigDecimal("4"),
                ZoneType.REFRIGERATED, "0.30", "0.50", "0.02", "30.0", "22.0", "10.0");
        cat("FROZEN", "冻品（冻鸡爪）", new BigDecimal("-25"), new BigDecimal("-15"),
                ZoneType.FROZEN, "0.45", "0.40", "0.01", "18.0", "12.0", "6.0");
        cat("SEAFOOD", "水产（基围虾）", new BigDecimal("-2"), new BigDecimal("4"),
                ZoneType.REFRIGERATED, "0.40", "0.70", "0.03", "35.0", "22.0", "8.0");
    }

    private void cat(String code, String name, BigDecimal minT, BigDecimal maxT, ZoneType zone,
                     String storageRate, String sortRate, String agreedLoss,
                     String pa, String pb, String pp) {
        Category c = new Category();
        c.setCode(code);
        c.setName(name);
        c.setMinArrivalTemp(minT);
        c.setMaxArrivalTemp(maxT);
        c.setRecommendedZone(zone);
        c.setStorageFeeRate(new BigDecimal(storageRate));
        c.setSortFeeRate(new BigDecimal(sortRate));
        c.setAgreedLossRate(new BigDecimal(agreedLoss));
        c.setDefaultPriceA(new BigDecimal(pa));
        c.setDefaultPriceB(new BigDecimal(pb));
        c.setDefaultPriceProcess(new BigDecimal(pp));
        categoryRepository.save(c);
    }

    private void seedZones() {
        Zone z1 = zone("CW-01", "1号冰温保鲜库", ZoneType.CHILLED, new BigDecimal("0"),
                new BigDecimal("5000"));
        Zone z2 = zone("LC-01", "1号冷藏库", ZoneType.REFRIGERATED, new BigDecimal("3"),
                new BigDecimal("8000"));
        Zone z3 = zone("FZ-01", "1号冷冻库", ZoneType.FROZEN, new BigDecimal("-18"),
                new BigDecimal("6000"));
        locations(z1, 3, new BigDecimal("1800"));
        locations(z2, 4, new BigDecimal("2200"));
        locations(z3, 3, new BigDecimal("2000"));
    }

    private Zone zone(String code, String name, ZoneType type, BigDecimal temp, BigDecimal cap) {
        Zone z = new Zone();
        z.setCode(code);
        z.setName(name);
        z.setType(type);
        z.setTargetTemp(temp);
        z.setCurrentTemp(temp);
        z.setCapacityKg(cap);
        z.setOccupiedKg(BigDecimal.ZERO);
        z.setFault(false);
        return zoneRepository.save(z);
    }

    private void locations(Zone z, int count, BigDecimal cap) {
        for (int i = 1; i <= count; i++) {
            Location l = new Location();
            l.setCode(z.getCode() + "-" + String.format("%02d", i));
            l.setZoneId(z.getId());
            l.setCapacityKg(cap);
            l.setOccupiedKg(BigDecimal.ZERO);
            locationRepository.save(l);
        }
    }

    private void seedLocks() {
        Customer c1 = customerRepository.findAll().get(0);
        PriceLock lock = new PriceLock();
        lock.setLockNo("K-SEED-0001");
        lock.setCustomerId(c1.getId());
        lock.setCategoryCode("GRAPE");
        lock.setLockedPrice(new BigDecimal("11.50"));
        lock.setLockedQty(new BigDecimal("800"));
        lock.setFulfilledQty(BigDecimal.ZERO);
        lock.setValidFrom(LocalDate.now().minusDays(2));
        lock.setValidTo(LocalDate.now().plusDays(5));
        lock.setStatus(LockStatus.ACTIVE);
        lock.setRemark("葡萄产季锁价协议，县城鲜果批发行锁定 800kg");
        priceLockRepository.save(lock);
    }
}
