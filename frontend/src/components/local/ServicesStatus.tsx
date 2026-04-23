'use client';

import React, { useState, useRef, useEffect } from 'react';
import { Zap, Droplets, Wifi, Fuel, CheckCircle, XCircle, AlertTriangle, Clock, RefreshCw, ChevronLeft, ChevronRight, Heart, Grid3X3, Bell } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { useRegion, Region } from '@/contexts/RegionContext';
import { Drawer, DrawerContent, DrawerHeader, DrawerTitle } from '@/components/ui/drawer';

interface ServiceStatus {
  id: string;
  name: string;
  nameEn: string;
  icon: React.ElementType;
  status: 'active' | 'inactive' | 'partial' | 'maintenance';
  lastUpdate: string;
  lastUpdateEn: string;
  message: string;
  messageEn: string;
  color: string;
  notify?: boolean;
}

const qudsayaCenterServices: ServiceStatus[] = [
  {
    id: 'electricity',
    name: 'الكهرباء',
    nameEn: 'Electricity',
    icon: Zap,
    status: 'partial',
    lastUpdate: 'منذ 5 دقائق',
    lastUpdateEn: '5 min ago',
    message: 'مقطوع جزئياً - حي المركز والغربي',
    messageEn: 'Partial outage - Center & West districts',
    color: 'yellow',
    notify: true
  },
  {
    id: 'water',
    name: 'الماء',
    nameEn: 'Water',
    icon: Droplets,
    status: 'active',
    lastUpdate: 'منذ 10 دقائق',
    lastUpdateEn: '10 min ago',
    message: 'متوفر في جميع الأحياء',
    messageEn: 'Available in all areas',
    color: 'blue'
  },
  {
    id: 'internet',
    name: 'الإنترنت',
    nameEn: 'Internet',
    icon: Wifi,
    status: 'active',
    lastUpdate: 'منذ 2 دقيقة',
    lastUpdateEn: '2 min ago',
    message: 'الاتصال مستقر - جميع الشبكات',
    messageEn: 'Stable connection - All networks',
    color: 'purple'
  },
  {
    id: 'fuel',
    name: 'الوقود',
    nameEn: 'Fuel',
    icon: Fuel,
    status: 'partial',
    lastUpdate: 'منذ 15 دقيقة',
    lastUpdateEn: '15 min ago',
    message: 'المازوت متوفر - البنزين محدود',
    messageEn: 'Diesel available - Gas limited',
    color: 'orange',
    notify: true
  },
  {
    id: 'gas',
    name: 'الغاز',
    nameEn: 'Gas',
    icon: Fuel,
    status: 'active',
    lastUpdate: 'منذ 30 دقيقة',
    lastUpdateEn: '30 min ago',
    message: 'متوفر في جميع المحطات',
    messageEn: 'Available at all stations',
    color: 'cyan'
  }
];

const qudsayaDahiaServices: ServiceStatus[] = [
  {
    id: 'electricity',
    name: 'الكهرباء',
    nameEn: 'Electricity',
    icon: Zap,
    status: 'active',
    lastUpdate: 'منذ 3 دقائق',
    lastUpdateEn: '3 min ago',
    message: 'متوفر في جميع أنحاء الضاحية',
    messageEn: 'Available throughout Dahia',
    color: 'yellow'
  },
  {
    id: 'water',
    name: 'الماء',
    nameEn: 'Water',
    icon: Droplets,
    status: 'partial',
    lastUpdate: 'منذ 8 دقائق',
    lastUpdateEn: '8 min ago',
    message: 'انخفاض الضغط في الحي الشرقي',
    messageEn: 'Low pressure in East district',
    color: 'blue',
    notify: true
  },
  {
    id: 'internet',
    name: 'الإنترنت',
    nameEn: 'Internet',
    icon: Wifi,
    status: 'active',
    lastUpdate: 'منذ 1 دقيقة',
    lastUpdateEn: '1 min ago',
    message: 'الاتصال ممتاز - سرعة عالية',
    messageEn: 'Excellent connection - High speed',
    color: 'purple'
  },
  {
    id: 'fuel',
    name: 'الوقود',
    nameEn: 'Fuel',
    icon: Fuel,
    status: 'active',
    lastUpdate: 'منذ 20 دقيقة',
    lastUpdateEn: '20 min ago',
    message: 'متوفر بكثرة - جميع المحطات',
    messageEn: 'Available - All stations',
    color: 'orange'
  },
  {
    id: 'gas',
    name: 'الغاز',
    nameEn: 'Gas',
    icon: Fuel,
    status: 'inactive',
    lastUpdate: 'منذ 45 دقيقة',
    lastUpdateEn: '45 min ago',
    message: 'انقطاع كامل - صيانة طارئة',
    messageEn: 'Full outage - Emergency maintenance',
    color: 'cyan',
    notify: true
  }
];

const dataByRegion: Record<Region, ServiceStatus[]> = {
  'qudsaya-center': qudsayaCenterServices,
  'qudsaya-dahia': qudsayaDahiaServices
};

const getStatusConfig = (status: ServiceStatus['status']) => {
  switch (status) {
    case 'active':
      return {
        icon: CheckCircle,
        text: 'متوفر',
        textEn: 'Available',
        colorClass: 'text-emerald-600',
        bgClass: 'bg-emerald-50',
        borderClass: 'border-emerald-200',
        gradientClass: 'from-emerald-500 to-emerald-600',
        ringClass: 'ring-emerald-200'
      };
    case 'inactive':
      return {
        icon: XCircle,
        text: 'مقطوع',
        textEn: 'Outage',
        colorClass: 'text-red-600',
        bgClass: 'bg-red-50',
        borderClass: 'border-red-200',
        gradientClass: 'from-red-500 to-red-600',
        ringClass: 'ring-red-200'
      };
    case 'partial':
      return {
        icon: AlertTriangle,
        text: 'جزئي',
        textEn: 'Partial',
        colorClass: 'text-amber-600',
        bgClass: 'bg-amber-50',
        borderClass: 'border-amber-200',
        gradientClass: 'from-amber-500 to-amber-600',
        ringClass: 'ring-amber-200'
      };
    case 'maintenance':
      return {
        icon: Clock,
        text: 'صيانة',
        textEn: 'Maintenance',
        colorClass: 'text-blue-600',
        bgClass: 'bg-blue-50',
        borderClass: 'border-blue-200',
        gradientClass: 'from-blue-500 to-blue-600',
        ringClass: 'ring-blue-200'
      };
  }
};

const statusFilters = [
  { id: 'all', name: 'الكل', nameEn: 'All', icon: RefreshCw },
  { id: 'active', name: 'متوفر', nameEn: 'Available', icon: CheckCircle },
  { id: 'partial', name: 'جزئي', nameEn: 'Partial', icon: AlertTriangle },
  { id: 'inactive', name: 'مقطوع', nameEn: 'Outage', icon: XCircle },
];

export default function ServicesStatus() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const { region, regionName } = useRegion();
  const services = dataByRegion[region];

  const [activeStatus, setActiveStatus] = useState('all');
  const [favorites, setFavorites] = useState<string[]>([]);
  const [canScrollLeft, setCanScrollLeft] = useState(false);
  const [canScrollRight, setCanScrollRight] = useState(true);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  const filteredServices = services.filter(service => {
    if (activeStatus === 'all') return true;
    return service.status === activeStatus;
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

  // Count by status
  const activeCount = services.filter(s => s.status === 'active').length;
  const partialCount = services.filter(s => s.status === 'partial').length;
  const inactiveCount = services.filter(s => s.status === 'inactive').length;

  return (
    <section className="py-2">
      <div className="max-w-7xl mx-auto">
        {/* Section Title with Live Badge */}
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-xl font-bold text-gray-900 flex items-center gap-2">
            ⚡ {isArabic ? 'حالة الخدمات' : 'Services Status'}
            <span className="flex items-center gap-1 px-2 py-0.5 bg-emerald-100 text-emerald-700 text-xs font-bold rounded-full">
              <span className="w-1.5 h-1.5 bg-emerald-500 rounded-full animate-pulse" />
              Live
            </span>
          </h2>
          <div className="flex items-center gap-2 text-xs">
            <span className="flex items-center gap-1 px-2 py-1 bg-emerald-50 text-emerald-700 rounded-full font-medium">
              <CheckCircle className="w-3 h-3" /> {activeCount}
            </span>
            <span className="flex items-center gap-1 px-2 py-1 bg-amber-50 text-amber-700 rounded-full font-medium">
              <AlertTriangle className="w-3 h-3" /> {partialCount}
            </span>
            {inactiveCount > 0 && (
              <span className="flex items-center gap-1 px-2 py-1 bg-red-50 text-red-700 rounded-full font-medium">
                <XCircle className="w-3 h-3" /> {inactiveCount}
              </span>
            )}
          </div>
        </div>

        {/* Status Filters - Airbnb Style */}
        <div className="flex gap-6 mb-4 overflow-x-auto pb-2 scrollbar-hide">
          {statusFilters.map((filter) => {
            const Icon = filter.icon;
            const isActive = activeStatus === filter.id;
            return (
              <button
                key={filter.id}
                onClick={() => setActiveStatus(filter.id)}
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
              const statusConfig = getStatusConfig(service.status);
              const StatusIcon = statusConfig.icon;
              const ServiceIcon = service.icon;
              const isFavorite = favorites.includes(service.id);

              return (
                <div key={service.id} className="flex-shrink-0 w-[200px] sm:w-[220px] md:w-[240px] group cursor-pointer">
                  <div className={`relative aspect-square rounded-xl overflow-hidden mb-2 border-2 ${statusConfig.borderClass} bg-gradient-to-br from-gray-50 to-white`}>
                    {/* Gradient Top Bar */}
                    <div className={`h-2 bg-gradient-to-r ${statusConfig.gradientClass}`} />
                    
                    {/* Bell notification */}
                    {service.notify && (
                      <div className="absolute top-4 right-2">
                        <Bell className="w-4 h-4 text-amber-500 animate-bounce" />
                      </div>
                    )}

                    {/* Service Icon */}
                    <div className="absolute inset-0 flex items-center justify-center mt-2">
                      <div className={`w-20 h-20 ${statusConfig.bgClass} rounded-2xl flex items-center justify-center shadow-lg ring-2 ${statusConfig.ringClass} group-hover:scale-110 transition-transform`}>
                        <ServiceIcon className={`w-10 h-10 ${statusConfig.colorClass}`} />
                      </div>
                    </div>

                    {/* Favorite Button */}
                    <button onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }} className="absolute top-4 left-2 p-1.5 transition-transform hover:scale-110">
                      <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-gray-400'}`} />
                    </button>

                    {/* Status Badge */}
                    <div className={`absolute bottom-2 left-2 right-2 flex items-center justify-between px-3 py-2 rounded-xl ${statusConfig.bgClass} backdrop-blur-sm`}>
                      <div className="flex items-center gap-1">
                        <StatusIcon className={`w-4 h-4 ${statusConfig.colorClass}`} />
                        <span className={`text-xs font-bold ${statusConfig.colorClass}`}>
                          {isArabic ? statusConfig.text : statusConfig.textEn}
                        </span>
                      </div>
                      <div className="flex items-center gap-1 text-xs text-gray-400">
                        <Clock className="w-3 h-3" />
                        {isArabic ? service.lastUpdate : service.lastUpdateEn}
                      </div>
                    </div>
                  </div>

                  <div>
                    <h3 className="text-sm font-medium text-gray-900 mb-1">{isArabic ? service.name : service.nameEn}</h3>
                    <p className="text-xs text-gray-500 line-clamp-2">{isArabic ? service.message : service.messageEn}</p>
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
                <div className="relative aspect-square rounded-xl overflow-hidden mb-2 border-2 border-dashed border-gray-300 hover:border-gray-400 transition-colors bg-gradient-to-br from-gray-50 to-gray-100 flex items-center justify-center">
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
              ⚡ {isArabic ? 'حالة جميع الخدمات' : 'All Services Status'}
            </DrawerTitle>
          </DrawerHeader>
          <div className="p-4 overflow-y-auto">
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {filteredServices.map((service) => {
                const statusConfig = getStatusConfig(service.status);
                const ServiceIcon = service.icon;
                const isFavorite = favorites.includes(service.id);
                return (
                  <div key={service.id} className="group cursor-pointer">
                    <div className={`relative aspect-square rounded-xl overflow-hidden mb-2 border-2 ${statusConfig.borderClass} bg-gradient-to-br from-gray-50 to-white`}>
                      <div className={`h-2 bg-gradient-to-r ${statusConfig.gradientClass}`} />
                      <div className="absolute inset-0 flex items-center justify-center mt-2">
                        <div className={`w-16 h-16 ${statusConfig.bgClass} rounded-2xl flex items-center justify-center shadow-lg`}>
                          <ServiceIcon className={`w-8 h-8 ${statusConfig.colorClass}`} />
                        </div>
                      </div>
                      <button onClick={(e) => { e.stopPropagation(); toggleFavorite(service.id); }} className="absolute top-2 left-2 p-1.5 transition-transform hover:scale-110">
                        <Heart className={`w-5 h-5 ${isFavorite ? 'fill-rose-500 text-rose-500' : 'text-gray-400'}`} />
                      </button>
                    </div>
                    <h3 className="text-sm font-medium text-gray-900 line-clamp-1">{isArabic ? service.name : service.nameEn}</h3>
                    <p className="text-xs text-gray-500 truncate">{isArabic ? service.message : service.messageEn}</p>
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
