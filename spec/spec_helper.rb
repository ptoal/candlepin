RSpec.configure do |config|
  config.before(:each) do
    @cp = Candlepin.new('admin', 'admin')
    @owners = []
    @products = []
    @dist_versions = []
    @users = []
    @roles = []
    @rules = nil
  end

  config.before(:each, :type => :virt) do
    pending("candlepin running in standalone mode") if is_hosted?
    @owner = create_owner random_string('virt_owner')
    @user = user_client(@owner, random_string('virt_user'))

    # Create a sub for a virt limited product:
    @virt_limit_product = create_product(nil, nil, {
      :attributes => {
        :virt_limit => 3
      }
    })

    #create two subs, to do migration testing
    @sub1 = @cp.create_subscription(@owner['key'],
      @virt_limit_product.id, 10)
    @sub2 = @cp.create_subscription(@owner['key'],
      @virt_limit_product.id, 10)
    @cp.refresh_pools(@owner['key'])

    @pools = @user.list_pools :owner => @owner.id, \
      :product => @virt_limit_product.id
    @pools.size.should == 2
    @virt_limit_pool = @pools[0]

    # Setup two virt guest consumers:
    @uuid1 = random_string('system.uuid')
    @uuid2 = random_string('system.uuid')
    @guest1 = @user.register(random_string('guest'), :system, nil,
      {'virt.uuid' => @uuid1, 'virt.is_guest' => 'true'}, nil, nil, [], [])
    @guest1_client = Candlepin.new(username=nil, password=nil,
        cert=@guest1['idCert']['cert'],
        key=@guest1['idCert']['key'])

    @guest2 = @user.register(random_string('guest'), :system, nil,
      {'virt.uuid' => @uuid2, 'virt.is_guest' => 'true'}, nil, nil, [], [])
    @guest2_client = Candlepin.new(username=nil, password=nil,
        cert=@guest2['idCert']['cert'],
        key=@guest2['idCert']['key'])
  end

  config.after(:each) do
    @roles.reverse_each { |r| @cp.delete_role r['id'] }
    @owners.reverse_each { |owner| @cp.delete_owner owner['key'] }
    @users.reverse_each { |user| @cp.delete_user user['username'] }
    @products.reverse_each { |product| @cp.delete_product product['id'] }
    @dist_versions.reverse_each { |dist_version| @cp.delete_distributor_version dist_version['id'] }

    # restore the original rules
    if (@rules)
      @cp.delete_rules
    end
    # TODO:  delete products?
  end
end

module VirtHelper
  def find_guest_virt_pool(guest_client, guest_uuid)
    pools = guest_client.list_pools :consumer => guest_uuid
    return pools.find_all { |i| !i['sourceEntitlement'].nil? }[0]
  end
end
