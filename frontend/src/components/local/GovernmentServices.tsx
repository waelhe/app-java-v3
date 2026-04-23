'use client';

import React, { useState, useRef, useEffect } from 'react';
import { Building2, FileText, Car, Home, Users, CreditCard, Clock, ChevronLeft, ChevronRight, Heart, Grid3X3, Phone, MapPin } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

interface GovService {
  id: string;
  name: string;
  nameEn: string;
  icon: React.ElementType;
  color: string;
  description: string;
  descriptionEn: string;
  time: string;
  address?: string;
  addressEn?: string;
  phone?: string;
  featured?: boolean;
}

const qudsayaCenterServices: GovService[] = [
  {
    id: '1',
    name: 'الأحوال المدنية - قدسيا',
    nameEn: 'Civil Registry - Qudsaya',
    icon: Users,
    color: 'bg-blue-600',
    description: 'إخراج قيد، هوية، جواز سفر',
    descriptionEn: 'ID, Passport, Records',
    time: '8:00 - 14:00',
    address: 'مبنى البلدية - قدسيا',
    addressEn: 'Municipality Building - Qudsaya',
    phone: '011-6661234',
    featured: true
  },
  {
    id: '2',
    name: 'مديرية المرور - قدسيا',
    nameEn: 'Traffic Dept - Qudsaya',
    icon: Car,
    color: 'bg-amber-600',
    description: 'رخص قيادة، مخالفات، تسجيل',
    descriptionEn: 'Licenses, Violations, Registration',
    time: '8:00 - 14:00',
    address: 'طريق دمشق-قدسيا',
    addressEn: 'Damascus-Qudsaya Road',
    phone: '011-6662345',
    featured: true
  },
  {
    id: '3',
    name: 'بلدية قدسيا',
    nameEn: 'Qudsaya Municipality',
    icon: Building2,
    color: 'bg-emerald-600',
    description: 'رخص بناء، عقود، تنظيم',
    descriptionEn: 'Building Permits, Contracts',
    time: '8:00 - 15:00',
    address: 'ساحة قدسيا المركزية',
    addressEn: 'Qudsaya Central Square',
    phone: '011-6663456',
    featured: true
  },
  {
    id: '4',
    name: 'السجل العقاري - قدسيا',
    nameEn: 'Real Estate Registry - Qudsaya',
    icon: Home,
    color: 'bg-purple-600',
    description: 'بيع، شراء، فراغ، توثيق',
    descriptionEn: 'Sale, Purchase, Transfer',
    time: '8:00 - 14:00',
    address: 'مبنى المحكمة - قدسيا',
    addressEn: 'Court Building - Qudsaya',
    phone: '011-6664567'
  },
  {
    id: '5',
    name: 'مديرية المالية - قدسيا',
    nameEn: 'Finance Dept - Qudsaya',
    icon: CreditCard,
    color: 'bg-red-600',
    description: 'دفع الضرائب والرسوم',
    descriptionEn: 'Pay Taxes & Fees',
    time: '8:00 - 14:00',
    address: 'مبنى البلدية - قدسيا',
    addressEn: 'Municipality Building - Qudsaya',
    phone: '011-6665678'
  },
  {
    id: '6',
    name: 'نماذج وطلبات إلكترونية',
    nameEn: 'E-Forms & Applications',
    icon: FileText,
    color: 'bg-teal-600',
    description: 'تحميل النماذج الرسمية',
    descriptionEn: 'Download Official Forms',
    time: 'أونلاين 24/7'
  },
];

const qudsayaDahiaServices: GovService[] = [
  {
    id: '1',
    name: 'الأحوال المدنية - الضاحية',
    nameEn: 'Civil Registry - Dahia',
    icon: Users,
    color: 'bg-blue-600',
    description: 'إخراج قيد، هوية، جواز سفر',
    descriptionEn: 'ID, Passport, Records',
    time: '8:00 - 14:00',
    address: 'مركز الضاحية الإداري',
    addressEn: 'Dahia Administrative Center',
    phone: '011-7771234',
    featured: true
  },
  {
    id: '2',
    name: 'مديرية المرور - الضاحية',
    nameEn: 'Traffic Dept - Dahia',
    icon: Car,
    color: 'bg-amber-600',
    description: 'رخص قيادة، مخالفات، تسجيل',
    descriptionEn: 'Licenses, Violations, Registration',
    time: '8:00 - 14:00',
    address: 'مدخل الضاحية الرئيسي',
    addressEn: 'Main Dahia Entrance',
    phone: '011-7772345',
    featured: true
  },
  {
    id: '3',
    name: 'بلدية الضاحية',
    nameEn: 'Dahia Municipality',
    icon: Building2,
    color: 'bg-emerald-600',
    description: 'رخص بناء، عقود، تنظيم',
    descriptionEn: 'Building Permits, Contracts',
    time: '8:00 - 15:00',
    address: 'ساحة الضاحية المركزية',
    addressEn: 'Dahia Central Square',
    phone: '011-7773456',
    featured: true
  },
  {
    id: '4',
    name: 'السجل العقاري - الضاحية',
    nameEn: 'Real Estate Registry - Dahia',
    icon: Home,
    color: 'bg-purple-600',
    description: 'بيع، شراء، فراغ، توثيق',
    descriptionEn: 'Sale, Purchase, Transfer',
    time: '8:00 - 14:00',
    address: 'مبنى المحكمة - الضاحية',
    addressEn: 'Court Building - Dahia',
    phone: '011-7774567'
  },
  {
    id: '5',
    name: 'مديرية المالية - الضاحية',
    nameEn: 'Finance Dept - Dahia',
    icon: CreditCard,
    color: 'bg-red-600',
    description: 'دفع الضرائب والرسوم',
    descriptionEn: 'Pay Taxes & Fees',
    time: '8:00 - 14:00',
    address: 'مبنى البلدية - الضاحية',
    addressEn: 'Municipality Building - Dahia',
    phone: '011-7775678'
  },
  {
    id: '6',
    name: 'نماذج وطلبات إلكترونية',
    nameEn: 'E-Forms & Applications',
    icon: FileText,
    color: 'bg-teal-600',
    description: 'تحميل النماذج الرسمية',
    descriptionEn: 'Download Official Forms',
    time: 'أونلاين 24/7'
  },
];

const dataByRegion: Record<Region, GovService[]> = {
  'qudsaya-center': qudsayaCenterServices,
  'qudsaya-dahia': qudsayaDahiaServices
};

const categoryFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: Building2 },
  { id: 'civil', name: 'أحوال مدنية', nameEn: 'Civil Registry', icon: Users },
  { id: 'traffic', name: 'مرور', nameEn: 'Traffic', icon: Car },
  { id: 'municipality', name: 'بلدية', nameEn: 'Municipality', icon: Building2 },
  { id: 'realestate', name: 'عقارات', nameEn: 'Real Estate', icon: Home },
  { id: 'finance', name: 'مالية', nameEn: 'Finance', icon: CreditCard },
];

export default function GovernmentServices() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region, regionName } = useRegion();
  const services = dataByRegion[region];

  const [activeCategory, setActiveCategory] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredServices = services.filter(service => {
    if (activeCategory === 'all') return true;
    if (activeCategory === 'civil') return service.name.includes('أحوال') || service.nameEn.includes('Civil');
    if (activeCategory === 'traffic') return service.name.includes('مرور') || service.nameEn.includes('Traffic');
    if (activeCategory === 'municipality') return service.name.includes('بلدية') || service.nameEn.includes('Municipality');
    if (activeCategory === 'realestate') return service.name.includes('عقاري') || service.nameEn.includes('Real Estate');
    if (activeCategory === 'finance') return service.name.includes('مالية') || service.nameEn.includes('Finance');
    return true;
  });

  const checkScroll = () => {
    if (scrollRef.current) {
      const { scrollLeft, scrollWidth, clientWidth } = scrollRef.current;
      setCanScrollLeft(scrollLeft > 0);
      setCanScrollRight(scrollLeft < scrollWidth - clientWidth - 10);
    }
  };

  useEffect(() => {
    checkScroll();
    const scrollEl = scrollRef.current;
    if (scrollEl) {
      scrollEl.addEventListener('scroll', checkScroll);
      return () => scrollEl.removeEventListener('scroll', checkScroll);
    }
  }, [filteredServices]);

  const scroll = (direction: 'left' | 'right') => {
    if (scrollRef.current) {
      scrollRef.current.scrollBy({ left: direction === 'left' ? -280 : 280, behavior: 'smooth' });
    }
  };

  const toggleFavorite = (id: string) => {
    setFavorites(prev => prev.includes(id) ? prev.filter(f => f !== id) : [...prev, id]);
  };

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title */}
        <h2 className="text-xl font-bold text-gray-900 mb-4">
          {isArabic ? '🏛️ خدمات حكومية' : '🏛️ Government Services'}
        </h2>

        {/* Category Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {categoryFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeCategory === filter.id;
            return (
              <button
                key={filter.id}
                onClick={() => setActiveCategory(filter.id)}
                className={`flex flex-col items-center gap-1.5 pb-2 min-w-[50px] transition-all border-b-2 ${
                  isActive ? 'border-gray-900 text-gray-900' : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-200'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span className="text-xs font-medium">{isArabic ? filter.name : filter.nameEn}</span>
              </button>
            );
          })}
        </div>

        <div className="relative">
          {canScrollLeft && (
            <button onClick={() => scroll('left')} className="absolute left-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronRight className="w-4 h-4 text-gray-700" />
            </button>
          )}
          {canScrollRight && (
            <button onClick={() => scroll('right')} className="absolute right-0 top-1/2 -translate-y-1/2 z-10 w-8 h-8 bg-white rounded-full shadow-lg flex items-center justify-center border border-gray-200 hover:scale-105 transition-transform">
              <ChevronLeft className="w-4 h-4 text-gray-700" />
            </button>
          )}

          <div ref={scrollRef} className="flex gap-4 overflow-x-auto pb-2 scroll-smooth scrollbar-hide" style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}>
            {filteredServices.slice(0, 8).map((service) => {
              const Icon = service.icon;
              const isFavorite = favorites.includes(service.id);
              return (
                <div key={service.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className="relative aspect-square rounded-xl overflow-hidden mb-2 bg-gradient-to-br from-slate-100 to-gray-200">
                    <div className={`absolute inset-0 ${service.color} opacity-20`} />
                    <div className="absolute inset-0 flex items-center justify-center">
                      <div className={`w-20 h-20 ${service.color} rounded-2xl flex items-center justify-center shadow-lg group-hover:scale-110 transition-transform`}>
                        <Icon className="w-10 h-10 text-white" />
                      </div>
                    </div>
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                    </button>
                    {service.featured && (
                      <span className="absolute bottom-2 left-2 px-2 py-0.5 bg-amber-500/90 text-white text-[10px] font-semibold rounded-full backdrop-blur-sm">
                        {isArabic ? 'مميز' : 'Featured'}
                      </span>
                    )}
                    <div className="absolute bottom-2 right-2 flex items-center gap-0.5 px-2 py-0.5 bg-black/60 backdrop-blur-sm rounded-full">
                      <Clock className="w-3 h-3 text-white" />
                      <span className="text-[10px] font-bold text-white">{service.time}</span>
                    </div>
                  </div>
                  <div>
                    <h3 className="text-sm font-medium text-gray-900 mb-1 line-clamp-1">{isArabic ? service.name : service.nameEn}</h3>
                    <p className="text-xs text-gray-500 mb-1 line-clamp-2">{isArabic ? service.description : service.descriptionEn}</p>
                    {service.address && (
                      <div className="flex items-center gap-1 text-xs text-gray-400">
                        <MapPin className="w-3 h-3" />
                        <span className="truncate">{isArabic ? service.address : service.addressEn}</span>
                      </div>
                    )}
                    {service.phone && (
                      <a href={`tel:${service.phone}`} onClick={(e) => e.stopPropagation()} className="flex items-center gap-1 text-xs text-blue-600 hover:underline mt-1">
                        <Phone className="w-3 h-3" />
                        {service.phone}
                      </a>
                    )}
                  </div>
                </div>
              );
            })}
            
            {/* بطاقة عرض الكل */}
            {filteredServices.length > 8 && (
              <div 
                onClick={() => setIsDrawerOpen(true)}
                className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer"
              >
                <div className="relative aspect-square rounded-xl overflow-hidden mb-2 border-2 border-dashed border-gray-300 hover:border-gray-400 transition-colors bg-gradient-to-br from-slate-50 to-gray-100 flex items-center justify-center">
                  <div className="text-center">
                    <Grid3X3 className="w-12 h-12 text-gray-400 mx-auto mb-2" />
                    <span className="text-lg font-bold text-gray-700">+{filteredServices.length - 8}</span>
                    <p className="text-sm text-gray-500">{isArabic ? 'عرض الكل' : 'View All'}</p>
                  </div>
                </div>
                <div className="text-center">
                  <p className="text-sm text-gray-600 font-medium">{isArabic ? 'تصفح جميع الخدمات' : 'Browse all services'}</p>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Drawer لعرض جميع الخدمات */}
      <Drawer open={isDrawerOpen} onOpenChange={setIsDrawerOpen}>
        <DrawerContent className="max-h-[85vh]">
          <DrawerHeader className="border-b">
            <DrawerTitle className="flex items-center gap-2">
              🏛️ {isArabic ? 'جميع الخدمات الحكومية' : 'All Government Services'}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {filteredServices.map((service) => {
                const Icon = service.icon;
                const isFavorite = favorites.includes(service.id);
                return (
                  <div key={service.id} className="group cursor-pointer">
                    <div className="relative aspect-square rounded-xl overflow-hidden mb-2 bg-gradient-to-br from-slate-100 to-gray-200">
                      <div className={`absolute inset-0 ${service.color} opacity-20`} />
                      <div className="absolute inset-0 flex items-center justify-center">
                        <div className={`w-16 h-16 ${service.color} rounded-2xl flex items-center justify-center shadow-lg`}>
                          <Icon className="w-8 h-8 text-white" />
                        </div>
                      </div>
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }} className="absolute top-2 right-2 p-1.5 transition-transform hover:scale-110">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-white drop-shadow-lg'}`} />
                      </button>
                      <div className="absolute bottom-2 right-2 flex items-center gap-0.5 px-2 py-0.5 bg-black/60 backdrop-blur-sm rounded-full">
                        <Clock className="w-3 h-3 text-white" />
                        <span className="text-[10px] font-bold text-white">{service.time}</span>
                      </div>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? service.name : service.nameEn}</h3>
                    <p className="text-xs text-gray-500 truncate">{isArabic ? service.description : service.descriptionEn}</p>
                  </div>
                );
              })}
            </div>
          </div>
        </DrawerContent>
      </Drawer>
    </section>
  );
}
